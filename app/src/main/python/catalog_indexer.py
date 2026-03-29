# -*- coding: utf-8 -*-
import base64
import json
import re
import struct
from pathlib import Path

from catalog_parser import read_int32_from_byte_array, read_object_from_byte_array


def _normalize_filename(filename: str):
    filename = (filename or "").strip().lower()
    if not filename:
        return []

    candidates = [filename]
    if filename.endswith('.atlas'):
        candidates.append(filename[:-6] + '.atlas.txt')
    if filename.endswith('.skel'):
        candidates.append(filename[:-5] + '.skel.bytes')
    if filename.endswith('.skel.txt'):
        candidates.append(filename[:-9] + '.skel.bytes')
    return list(dict.fromkeys(candidates))


def _extract_family_key(name: str):
    if not name:
        return None
    lowered = name.lower()
    stem = Path(lowered).stem

    if stem.endswith('.atlas'):
        stem = stem[:-6]
    if stem.endswith('.skel'):
        stem = stem[:-5]

    # Normalize common page / variant suffixes like _2, _3, -1, -2 so they map to the same family.
    stem = re.sub(r'([_-])(\d+)$', '', stem)
    return stem


def build_asset_index(catalog_content):
    if not catalog_content:
        return {
            'version': None,
            'assetsByExactKey': {},
            'assetsByBaseName': {},
            'bundlesByTargetHash': {}
        }

    bucket_array = base64.b64decode(catalog_content['m_BucketDataString'])
    key_array = base64.b64decode(catalog_content['m_KeyDataString'])
    extra_data = base64.b64decode(catalog_content['m_ExtraDataString'])
    entry_data = base64.b64decode(catalog_content['m_EntryDataString'])

    num_buckets = struct.unpack_from('<i', bucket_array, 0)[0]
    dependency_map = [None] * num_buckets
    data_offsets = []
    index = 4
    for i in range(num_buckets):
        data_offset = read_int32_from_byte_array(bucket_array, index)
        index += 4
        num_entries = read_int32_from_byte_array(bucket_array, index)
        index += 4
        entries = []
        for _ in range(num_entries):
            entry_index = read_int32_from_byte_array(bucket_array, index)
            index += 4
            entries.append(entry_index)
        data_offsets.append(data_offset)
        dependency_map[i] = entries

    keys = [read_object_from_byte_array(key_array, offset) for offset in data_offsets]

    number_of_entries = read_int32_from_byte_array(entry_data, 0)
    index = 4
    bundles = {}
    entries = []

    for m in range(number_of_entries):
        index += 4  # internal_id
        provider_index = read_int32_from_byte_array(entry_data, index)
        index += 4
        dependency_key_index = read_int32_from_byte_array(entry_data, index)
        index += 4
        index += 4  # dependency_hash
        data_index = read_int32_from_byte_array(entry_data, index)
        index += 4
        primary_key_index = read_int32_from_byte_array(entry_data, index)
        index += 4
        index += 4  # resource_type

        entries.append({
            'dependency_index': dependency_key_index,
            'primary_key_index': primary_key_index
        })

        if provider_index == 1 and data_index >= 0:
            bundle_info = read_object_from_byte_array(extra_data, data_index)
            if bundle_info:
                bundles[m] = {
                    'bundle_name': bundle_info.get('m_BundleName'),
                    'bundle_hash': bundle_info.get('m_Hash'),
                    'bundle_size': bundle_info.get('m_BundleSize'),
                    'download_name': str(keys[primary_key_index]) if primary_key_index < len(keys) else ''
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

    assets_by_exact = {}
    assets_by_base = {}
    bundles_by_hash = {}

    for i in range(len(entries)):
        primary_key_index = entries[i]['primary_key_index']
        raw_key = keys[primary_key_index] if primary_key_index < len(keys) else None
        if not isinstance(raw_key, str):
            continue

        asset_key = raw_key.lower()
        asset_base = Path(asset_key).name.lower()
        bundle_info = resolve_bundle_info(i)
        if not bundle_info:
            continue

        target_hash = bundle_info.get('bundle_name')
        family_key = _extract_family_key(asset_base)
        asset_info = {
            'assetKey': asset_key,
            'baseName': asset_base,
            'bundleName': bundle_info.get('bundle_name'),
            'bundleHash': bundle_info.get('bundle_hash'),
            'bundleSize': bundle_info.get('bundle_size'),
            'downloadName': bundle_info.get('download_name'),
            'targetHash': target_hash,
            'familyKey': family_key
        }

        assets_by_exact[asset_key] = asset_info
        assets_by_base.setdefault(asset_base, []).append(asset_info)
        if target_hash:
            bundles_by_hash[target_hash] = {
                'bundleName': bundle_info.get('bundle_name'),
                'bundleHash': bundle_info.get('bundle_hash'),
                'bundleSize': bundle_info.get('bundle_size'),
                'downloadName': bundle_info.get('download_name')
            }

    return {
        'version': None,
        'assetsByExactKey': assets_by_exact,
        'assetsByBaseName': assets_by_base,
        'bundlesByTargetHash': bundles_by_hash
    }


def load_or_build_asset_index(output_dir, quality, version, catalog_content):
    index_path = Path(output_dir).joinpath(f"asset_index_{quality.lower()}_{version}.json")
    if index_path.exists():
        with open(index_path, 'r', encoding='utf-8') as f:
            return json.load(f)

    index = build_asset_index(catalog_content)
    index['version'] = version
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False)
    return index


def normalize_filename(filename: str):
    return _normalize_filename(filename)
