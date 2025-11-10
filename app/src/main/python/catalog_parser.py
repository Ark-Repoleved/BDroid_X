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

        # Clean up old physical catalogs to prevent using stale data in case of script failure
        for f in Path(output_dir).glob("catalog_*.json"):
            try:
                os.remove(f)
            except OSError as e:
                if progress_callback: progress_callback(f"Error removing old catalog {f}: {e}")

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

def parse_catalog_for_bundle_names(catalog_content):
    """
    Parses the catalog to create a map from file_id (e.g., 'char000104') to bundle_name.
    This logic is adapted from ReDustX.
    """
    if not catalog_content:
        return {}

    asset_map = {}

    # Decode base64 data
    bucket_array = base64.b64decode(catalog_content['m_BucketDataString'])
    key_array = base64.b64decode(catalog_content['m_KeyDataString'])
    extra_data = base64.b64decode(catalog_content['m_ExtraDataString'])
    entry_data = base64.b64decode(catalog_content['m_EntryDataString'])

    # --- Bucket and Key Parsing ---
    num_buckets = struct.unpack_from('<i', bucket_array, 0)[0]
    dependency_map = [None] * num_buckets
    data_offsets = []
    index = 4
    for i in range(num_buckets):
        data_offsets.append(read_int32_from_byte_array(bucket_array, index))
        index += 4
        num_entries = read_int32_from_byte_array(bucket_array, index)
        index += 4
        entries = []
        for _ in range(num_entries):
            entries.append(read_int32_from_byte_array(bucket_array, index))
            index += 4
        dependency_map[i] = entries

    keys = [read_object_from_byte_array(key_array, offset) for offset in data_offsets]

    # --- Entry and Bundle Parsing ---
    number_of_entries = read_int32_from_byte_array(entry_data, 0)
    index = 4
    bundles = {}
    entries = []
    for m in range(number_of_entries):
        internal_id = read_int32_from_byte_array(entry_data, index)
        index += 4
        provider_index = read_int32_from_byte_array(entry_data, index)
        index += 4
        dependency_key_index = read_int32_from_byte_array(entry_data, index)
        index += 4
        index += 4 # dependency_hash
        data_index = read_int32_from_byte_array(entry_data, index)
        index += 4
        primary_key_index = read_int32_from_byte_array(entry_data, index)
        index += 4
        index += 4 # resource_type

        entries.append({'dependency_index': dependency_key_index, 'primary_key_index': primary_key_index})

        if provider_index == 1 and data_index >= 0:
            bundle_info = read_object_from_byte_array(extra_data, data_index)
            bundles[m] = {
                'bundle_name': bundle_info.get('m_BundleName'),
                'path': bundle_info.get('m_BundleName'), # Simplified for this context
            }

    def resolve_bundle_info(entry_index):
        if entry_index in bundles:
            return bundles[entry_index]
        if entry_index < 0 or entry_index >= len(entries):
            return None
        dep_idx = entries[entry_index]['dependency_index']
        if dep_idx < 0 or dep_idx >= len(dependency_map):
            return None
        deps = dependency_map[dep_idx] or []
        for dep_entry in deps:
            info = bundles.get(dep_entry)
            if info:
                return info
        return None

    # --- Map asset keys to bundle names ---
    for i in range(len(entries)):
        primary_key_index = entries[i]['primary_key_index']
        asset_key = keys[primary_key_index]
        if not isinstance(asset_key, str):
            continue

        # Extract file_id like 'char000104' from asset_key like 'assets/asset/character/char000104/char000104.skel.bytes'
        match = re.search(r'(cutscene_char\d{6}|char\d{6}|illust_dating\d+|illust_special\d+|illust_talk\d+|npc\d+|specialillust\w+|storypack\w+|\bRhythmHitAnim\b)', asset_key, re.IGNORECASE)
        if not match:
            continue
        
        matched_string = match.group(1).lower()
        
        # Determine asset type from the matched key itself
        if matched_string.startswith('cutscene_'):
            asset_type = "cutscene"
            file_id = matched_string.replace('cutscene_', '')
        else:
            # If it doesn't have the cutscene_ prefix, it's for the 'idle' slot.
            asset_type = "idle"
            file_id = matched_string

        # For 'idle' animations, only accept the .skel.bytes file to ensure
        # we get the correct bundle hash, not the hash for the atlas or png.
        if asset_type == "idle" and not asset_key.lower().endswith('.skel.bytes'):
            continue
        
        bundle_info = resolve_bundle_info(i)
        if bundle_info and 'bundle_name' in bundle_info:
            bundle_name = bundle_info['bundle_name']
            
            if file_id not in asset_map:
                asset_map[file_id] = {}
            
            # Store the bundle name based on the asset_type derived from the asset key
            asset_map[file_id][asset_type] = bundle_name

    return asset_map
