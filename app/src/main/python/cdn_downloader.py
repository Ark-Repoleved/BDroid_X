# -*- coding: utf-8 -*-
import base64
import json
import os
import re
import requests
import struct
from pathlib import Path

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
    try:
        from java.net import URL
        from java.lang import String

        url = URL("https://mt.bd2.pmang.cloud/MaintenanceInfo")
        conn = url.openConnection()
        conn.setRequestMethod("PUT")
        conn.setRequestProperty("accept", "*/*")
        conn.setRequestProperty("accept-encoding", "gzip")
        conn.setRequestProperty("connection", "close")
        conn.setRequestProperty("content-type", "multipart/form-data")
        conn.setRequestProperty("host", "mt.bd2.pmang.cloud")
        conn.setRequestProperty("user-agent", "UnityPlayer/2022.3.22f1 (UnityWebRequest/1.0, libcurl/8.5.0-DEV)")
        conn.setDoOutput(True)

        # Write data 'EAQ='
        out = conn.getOutputStream()
        data_bytes = b"EAQ="
        out.write(data_bytes)
        out.flush()
        out.close()

        # Read JSON response
        response_code = conn.getResponseCode()
        if response_code == 200:
            input_stream = conn.getInputStream()
            
            # Use gzip stream if the response is gzipped
            # Actually HttpURLConnection handles gzip transparently in Android usually, but just in case:
            content_encoding = conn.getContentEncoding()
            if content_encoding and "gzip" in content_encoding.lower():
                from java.util.zip import GZIPInputStream
                input_stream = GZIPInputStream(input_stream)
                
            from java.io import BufferedReader, InputStreamReader
            reader = BufferedReader(InputStreamReader(input_stream))
            
            response_string = ""
            while True:
                line = reader.readLine()
                if line is None:
                    break
                response_string += line
                
            reader.close()
            
            response_json = json.loads(str(response_string))
            base64_data = response_json['data']
            binary_data = base64.b64decode(base64_data)

            maintenance_response = maintenance_info_pb2.MaintenanceInfoResponse()
            maintenance_response.ParseFromString(binary_data)

            return maintenance_response.market_info.bundle_version if quality == 'HD' else maintenance_response.market_info.bundle_version_sd
        else:
            print(f"Failed to get CDN version. HTTP Code: {response_code}")
    except Exception as e:
        print(f"Native HttpURLConnection failed in get_cdn_version: {e}")
        import traceback
        traceback.print_exc()
        
    return None

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

def _try_download_bundle(url, output_file_path, bundle_size, progress_callback=None):
    """
    Helper function to attempt downloading a bundle from a given URL.
    Returns (success: bool, error_message: str or None)
    """
    try:
        response = requests.get(url, stream=True)
        response.raise_for_status()
        with open(output_file_path, 'wb') as file:
            total_downloaded = 0
            for chunk in response.iter_content(chunk_size=65536):
                if chunk:
                    file.write(chunk)
                    total_downloaded += len(chunk)
                    if progress_callback:
                        progress_callback(f"Downloading... {total_downloaded / 1024:.2f} KB / {bundle_size / 1024:.2f} KB")
        return True, None
    except requests.exceptions.RequestException as e:
        return False, str(e)


def _generate_download_urls(base_url, download_name, bundle_name, bundle_hash, internal_id=None):
    """Generate CDN URLs, preferring the actual path recorded in the catalog.

    Addressables primary keys may include a content hash that is not part of the
    object path on the CDN. The catalog's internal ID records that canonical path;
    guessing from the key or opaque bundle identity fails for such bundles.

    ``bundle_hash`` is retained for compatibility with legacy catalogs and call
    sites; current catalog download paths generally use ``m_BundleName`` instead.
    """
    urls = []

    if isinstance(internal_id, str) and internal_id:
        # Current catalogs use tokenized IDs such as
        # {BDNetwork.CdnInfo.Info}/Android/{BDNetwork.CdnInfo.Resolution}/
        # {BDNetwork.CdnInfo.Version}/bundle/path.bundle. Resolve these using
        # the versioned CDN root already selected for this catalog.
        cdn_path_marker = "/ServerData/Android/"
        cdn_root = (
            base_url.split(cdn_path_marker, 1)[0] + "/ServerData"
            if cdn_path_marker in base_url
            else None
        )
        if cdn_root:
            # base_url is .../Android/{quality}/{version}
            cdn_quality, cdn_version = base_url.rstrip("/").split("/Android/", 1)[1].split("/", 1)
            resolved_internal_id = re.sub(
                r"\{BDNetwork\.CdnInfo\.Info\}", cdn_root,
                internal_id, flags=re.IGNORECASE,
            )
            resolved_internal_id = re.sub(
                r"\{BDNetwork\.CdnInfo\.Resolution\}", cdn_quality,
                resolved_internal_id, flags=re.IGNORECASE,
            )
            resolved_internal_id = re.sub(
                r"\{BDNetwork\.CdnInfo\.Version\}", cdn_version,
                resolved_internal_id, flags=re.IGNORECASE,
            )
            resolved_internal_id = re.sub(
                r"\{UnityEngine\.AddressableAssets\.Addressables\.RuntimePath\}",
                base_url,
                resolved_internal_id,
                flags=re.IGNORECASE,
            )
            if "{" not in resolved_internal_id:
                parsed_internal_id = requests.compat.urlparse(resolved_internal_id)
                if parsed_internal_id.scheme in ("http", "https"):
                    urls.append(resolved_internal_id)
                elif not parsed_internal_id.scheme and not parsed_internal_id.netloc:
                    # Some catalog versions store a relative or absolute path
                    # rather than the standard CDN tokenized URL.
                    path = parsed_internal_id.path.lstrip("/")
                    if path.startswith("Android/"):
                        urls.append(f"{cdn_root}/{path}")
                    else:
                        urls.append(f"{base_url}/{path}")
                elif parsed_internal_id.netloc:
                    relative_path = resolved_internal_id.split("}", 1)[-1].lstrip("/")
                    if relative_path:
                        urls.append(f"{base_url}/{relative_path}")

    # Legacy catalogs may not have a usable internal ID. Keep their filename
    # conventions as fallbacks, but do not prefer guessed names over the ID.
    if download_name:
        urls.append(f"{base_url}/{download_name.lstrip('/')}")
        if bundle_hash and bundle_hash not in download_name and download_name.endswith(".bundle"):
            urls.append(f"{base_url}/{download_name[:-7]}_{bundle_hash}.bundle")
        if bundle_hash and bundle_hash in download_name:
            urls.append(f"{base_url}/{download_name.replace(f'_{bundle_hash}', '')}")

    if bundle_name:
        urls.append(f"{base_url}/{bundle_name.lstrip('/')}")
        if bundle_hash and bundle_name.endswith(".bundle"):
            urls.append(f"{base_url}/{bundle_name[:-7]}_{bundle_hash}.bundle")

    # Remove duplicates while preserving order.
    return list(dict.fromkeys(urls))


def find_and_download_bundle(catalog_content, version, quality, hashed_name, output_dir, progress_callback=None):
    if not catalog_content:
        return None, "Catalog content is missing or empty."

    # Dynamically find the AssetBundleProvider index from m_ProviderIds
    provider_ids = catalog_content.get('m_ProviderIds', [])
    bundle_provider = "UnityEngine.ResourceManagement.ResourceProviders.AssetBundleProvider"
    bundle_provider_index = provider_ids.index(bundle_provider) if bundle_provider in provider_ids else -1

    bucket_array = base64.b64decode(catalog_content['m_BucketDataString'])
    key_array = base64.b64decode(catalog_content['m_KeyDataString'])
    extra_data = base64.b64decode(catalog_content['m_ExtraDataString'])
    entry_data = base64.b64decode(catalog_content['m_EntryDataString'])
    internal_ids = catalog_content.get('m_InternalIds') or []

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
        internal_id_index = read_int32_from_byte_array(entry_data, index)
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

        if provider_index == bundle_provider_index and data_index >= 0:
            bundle_info = read_object_from_byte_array(extra_data, data_index)
            if bundle_info and bundle_info.get('m_BundleName') == hashed_name:
                raw_key = keys[primary_key_index] if 0 <= primary_key_index < len(keys) else ''
                download_name = str(raw_key) if isinstance(raw_key, str) else ''
                internal_id = (
                    internal_ids[internal_id_index]
                    if 0 <= internal_id_index < len(internal_ids)
                    else None
                )

                bundle_size = bundle_info.get('m_BundleSize', 0)
                bundle_name = bundle_info.get('m_BundleName')
                bundle_hash = bundle_info.get('m_Hash')
                if not isinstance(bundle_name, str) or not bundle_name:
                    return None, f"Catalog entry for bundle {hashed_name} has no valid bundle name."

                base_url = f"https://cdn.bd2.pmang.cloud/ServerData/Android/{quality}/{version}"
                urls_to_try = _generate_download_urls(
                    base_url, download_name, bundle_name, bundle_hash, internal_id
                )
                if not urls_to_try:
                    return None, f"No CDN download path found for bundle {hashed_name}."
                
                output_file_path = Path(output_dir).joinpath(bundle_name, bundle_hash, "__data")
                output_file_path.parent.mkdir(parents=True, exist_ok=True)

                last_error = None
                for i, url in enumerate(urls_to_try):
                    if progress_callback:
                        if i == 0:
                            progress_callback(f"Found bundle. Downloading from {url}...")
                        else:
                            progress_callback(f"Retrying with alternative URL ({i+1}/{len(urls_to_try)}): {url}...")
                    
                    success, error = _try_download_bundle(url, output_file_path, bundle_size, progress_callback)
                    
                    if success:
                        if progress_callback: progress_callback("Download complete.")
                        return str(output_file_path), None
                    else:
                        last_error = error
                        if progress_callback and i < len(urls_to_try) - 1:
                            progress_callback(f"Download failed: {error}. Trying alternative...")
                
                # All URLs failed
                error_message = f"Failed to download bundle after trying {len(urls_to_try)} URL(s). Last error: {last_error}"
                if progress_callback: progress_callback(error_message)
                return None, error_message

    return None, f"Bundle with hash {hashed_name} not found in catalog."
    
