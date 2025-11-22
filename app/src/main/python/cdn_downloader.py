# -*- coding: utf-8 -*-
import base64
import json
import os
import re
import requests
import struct
from pathlib import Path
from tqdm import tqdm

import maintenance_info_pb2

# Helper functions to simulate SerializationUtilities
class SerializationUtilities:
    class ObjectType:
        AsciiString = 0
        UnicodeString = 1
        UInt16 = 2
        UInt32 = 3
        Int32 = 4
        Hash128 = 5
        Type = 6
        JsonObject = 7

def get_cdn_version(quality):
    url = "https://mt.bd2.pmang.cloud/MaintenanceInfo"
    headers = {
        'accept': '*/*',
        'accept-encoding': 'gzip',
        'connection': 'close',
        'content-type': 'multipart/form-data',
        'host': 'mt.bd2.pmang.cloud',
        'user-agent': 'UnityPlayer/2022.3.22f1 (UnityWebRequest/1.0, libcurl/8.5.0-DEV)',
    }
    data = 'EAQ='
    response = requests.put(url, headers=headers, data=data)

    base64_data = response.json()['data']
    binary_data = base64.b64decode(base64_data)

    maintenance_response = maintenance_info_pb2.MaintenanceInfoResponse()
    maintenance_response.ParseFromString(binary_data)

    return maintenance_response.market_info.bundle_version if quality == 'HD' else maintenance_response.market_info.bundle_version_sd

def download_catalog(output_dir, quality, version, cache, lock, progress_callback=None):
    # Check the in-memory cache first
    if version in cache:
        if progress_callback: progress_callback(f"Catalog for version {version} found in memory cache.")
        return cache[version], None

    # If not in cache, acquire lock and check again
    with lock:
        # Double-check if another thread populated the cache while we were waiting for the lock
        if version in cache:
            if progress_callback: progress_callback(f"Catalog for version {version} found in memory cache after lock.")
            return cache[version], None

        # --- If still not in cache, proceed with download ---
        filename = Path(output_dir).joinpath(f"catalog_{version}.json")

        # Clean up old physical catalogs to prevent using stale data
        old_catalogs = list(Path(output_dir).glob("catalog_*.json"))
        for f in old_catalogs:
            try:
                os.remove(f)
            except OSError as e:
                if progress_callback: 
                    progress_callback(f"Error removing old catalog {f}: {e}")

        url = f"https://cdn.bd2.pmang.cloud/ServerData/Android/{quality}/{version}/catalog_alpha.json"
        if progress_callback: progress_callback(f"Downloading new catalog from {url}...")
        
        try:
            response = requests.get(url)
            response.raise_for_status()  # Raise an exception for bad status codes
            
            # Save the file to disk (for debugging and future single-use cases)
            with open(filename, 'wb') as file:
                file.write(response.content)
            
            # Parse the content and store it in the cache
            catalog_content = json.loads(response.content)
            cache[version] = catalog_content
            
            if progress_callback: progress_callback("Catalog downloaded and cached successfully.")
            return catalog_content, None
            
        except requests.exceptions.RequestException as e:
            error_message = f"Failed to download catalog: {e}"
            if progress_callback: progress_callback(error_message)
            return None, error_message
        except json.JSONDecodeError as e:
            error_message = f"Failed to parse downloaded catalog JSON: {e}"
            if progress_callback: progress_callback(error_message)
            return None, error_message

def read_int32_from_byte_array(byte_array, offset):
    return struct.unpack_from('<i', byte_array, offset)[0]

def read_object_from_byte_array(key_data, data_index):
    try:
        object_type = key_data[data_index]
        data_index += 1
        
        if object_type == SerializationUtilities.ObjectType.AsciiString:
            num = struct.unpack_from('<i', key_data, data_index)[0]
            data_index += 4
            return key_data[data_index:data_index + num].decode('ascii')
        
        elif object_type == SerializationUtilities.ObjectType.JsonObject:
            num3 = key_data[data_index]
            data_index += 1
            # Skip assembly and type names
            data_index += num3
            num4 = key_data[data_index]
            data_index += 1
            data_index += num4
            num5 = struct.unpack_from('<i', key_data, data_index)[0]
            data_index += 4
            json_data = key_data[data_index:data_index + num5].decode('utf-16')
            return json.loads(json_data)
        
        return None
    except Exception as ex:
        print(f"Exception during object parsing: {ex}")
        return None

def find_and_download_bundle(catalog_content, version, quality, hashed_name, output_dir, progress_callback=None):
    if not catalog_content:
        return None, "Catalog content is missing or empty."

    bucket_array = base64.b64decode(catalog_content['m_BucketDataString'])
    key_array = base64.b64decode(catalog_content['m_KeyDataString'])
    extra_data = base64.b64decode(catalog_content['m_ExtraDataString'])
    entry_data = base64.b64decode(catalog_content['m_EntryDataString'])

    num_buckets = struct.unpack_from('<i', bucket_array, 0)[0]
    data_offsets = []
    index = 4
    for _ in range(num_buckets):
        data_offsets.append(read_int32_from_byte_array(bucket_array, index))
        index += 4 # offset
        num_entries = read_int32_from_byte_array(bucket_array, index)
        index += 4 # num_entries
        index += 4 * num_entries # skip entries

    keys = [read_object_from_byte_array(key_array, offset) for offset in data_offsets]

    number_of_entries = read_int32_from_byte_array(entry_data, 0)
    index = 4
    for _ in range(number_of_entries):
        index += 4 # internal_id
        provider_index = read_int32_from_byte_array(entry_data, index)
        index += 4 # provider_index
        index += 4 # dependency_key
        index += 4 # dependency_hash
        data_index = read_int32_from_byte_array(entry_data, index)
        index += 4 # data_index
        primary_key_index = read_int32_from_byte_array(entry_data, index)
        index += 4 # primary_key
        index += 4 # resource_type

        if provider_index == 1 and data_index >= 0:
            bundle_info = read_object_from_byte_array(extra_data, data_index)
            if bundle_info and bundle_info.get('m_BundleName') == hashed_name:
                raw_key = keys[primary_key_index] if primary_key_index < len(keys) else ''
                download_name = str(raw_key)

                if not download_name:
                    continue

                bundle_size = bundle_info.get('m_BundleSize')
                
                url = f"https://cdn.bd2.pmang.cloud/ServerData/Android/{quality}/{version}/{download_name}"
                if progress_callback: progress_callback(f"Found bundle. Downloading from {url}...")

                bundle_name = bundle_info.get('m_BundleName')
                bundle_hash = bundle_info.get('m_Hash')
                output_file_path = Path(output_dir).joinpath(bundle_name, bundle_hash, "__data")
                output_file_path.parent.mkdir(parents=True, exist_ok=True)

                try:
                    response = requests.get(url, stream=True)
                    response.raise_for_status()
                    with open(output_file_path, 'wb') as file:
                        total_downloaded = 0
                        for chunk in response.iter_content(chunk_size=65536):  # 64KB chunks for better performance
                            if chunk:
                                file.write(chunk)
                                total_downloaded += len(chunk)
                                if progress_callback:
                                    progress_callback(f"Downloading... {total_downloaded / 1024:.2f} KB / {bundle_size / 1024:.2f} KB")
                    
                    if progress_callback: progress_callback("Download complete.")
                    return str(output_file_path), None
                except requests.exceptions.RequestException as e:
                    error_message = f"Failed to download bundle: {e}"
                    if progress_callback: progress_callback(error_message)
                    return None, error_message

    return None, f"Bundle with hash {hashed_name} not found in catalog."
    
