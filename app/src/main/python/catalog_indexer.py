# -*- coding: utf-8 -*-
import base64
import json
import re
import struct
from pathlib import Path

from catalog_parser import read_int32_from_byte_array, read_object_from_byte_array


INDEX_SCHEMA_VERSION = 7


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

    stem = _mod_asset_stem(filename)
    if stem:
        if re.fullmatch(r'illust_dating\d+', stem, re.IGNORECASE):
            candidates.extend([
                f'{stem}.prefab',
                f'vp_{stem}.asset',
                f'char/datingillust/{stem}.prefab',
                f'char/datingillust/vp_{stem}.asset',
            ])

        candidates.extend(_prefab_bridge_candidates(stem))

    return list(dict.fromkeys(candidates))


def _mod_asset_stem(asset_name: str):
    lowered = (asset_name or '').strip().lower()
    if not lowered:
        return None

    for suffix in ('.skel.bytes', '.atlas.txt'):
        if lowered.endswith(suffix):
            return lowered[:-len(suffix)]

    return Path(lowered).stem



def _prefab_bridge_candidates(stem: str):
    if not stem:
        return []

    candidates = []
    if re.fullmatch(r'char\d{6}', stem, re.IGNORECASE):
        candidates.extend([
            f'illust_{stem}_01.prefab',
            f'illust_{stem}_1.prefab',
        ])
    if re.fullmatch(r'npc\d{6}', stem, re.IGNORECASE):
        candidates.extend([
            f'illust_{stem}_01.prefab',
            f'illust_{stem}_1.prefab',
            f'illust_{stem}_2.prefab',
        ])
    return candidates



def _extract_family_key(name: str):
    if not name:
        return None
    lowered = name.lower()
    stem = Path(lowered).stem

    if stem.endswith('.atlas'):
        stem = stem[:-6]
    if stem.endswith('.skel'):
        stem = stem[:-5]

    dating_match = re.fullmatch(r'(illust_dating\d+)\.prefab', lowered, re.IGNORECASE)
    if dating_match:
        return dating_match.group(1).lower()

    dating_vp_match = re.fullmatch(r'(vp_illust_dating\d+)\.asset', lowered, re.IGNORECASE)
    if dating_vp_match:
        return dating_vp_match.group(1).lower()

    char_match = re.fullmatch(r'illust_(char\d{6})_\d+\.prefab', lowered, re.IGNORECASE)
    if char_match:
        return char_match.group(1).lower()

    npc_match = re.fullmatch(r'illust_(npc\d{6})_\d+\.prefab', lowered, re.IGNORECASE)
    if npc_match:
        return npc_match.group(1).lower()

    stem = re.sub(r'_(\d+)$', '', stem)
    return stem


def _score_asset_key_for_base(asset_key: str, asset_base: str):
    score = 0
    if asset_key == asset_base:
        score += 1000
    if '/censorship/' not in asset_key:
        score += 100
    if asset_key.endswith('/' + asset_base):
        score += 20
    score -= len(asset_key)
    return score


def _cleanup_stale_indexes(output_dir, quality, keep_version):
    pattern = f"asset_index_{quality.lower()}_*.json"
    for path in Path(output_dir).glob(pattern):
        if path.name != f"asset_index_{quality.lower()}_{keep_version}.json":
            try:
                path.unlink()
            except Exception:
                pass


def build_asset_index(catalog_content):
    if not catalog_content:
        return {
            'schemaVersion': INDEX_SCHEMA_VERSION,
            'version': None,
            'strings': [],
            'records': [],
            'assetsByExactKey': {},
            'assetsByBaseName': {}
        }

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
        internal_id_index = read_int32_from_byte_array(entry_data, index)
        index += 4
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
            'primary_key_index': primary_key_index,
            'internal_id_index': internal_id_index
        })

        if provider_index == bundle_provider_index and data_index >= 0:
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
        if not deps:
            return None
        info = bundles.get(deps[0])
        return info if info else None

    strings = []
    string_ids = {}
    records = []
    record_ids = {}
    assets_by_exact = {}
    base_candidates = {}

    def intern_string(value):
        if value is None:
            return -1
        value = str(value)
        existing = string_ids.get(value)
        if existing is not None:
            return existing
        idx = len(strings)
        strings.append(value)
        string_ids[value] = idx
        return idx

    def intern_record(asset_key, target_hash, family_key):
        asset_key_id = intern_string(asset_key)
        target_hash_id = intern_string(target_hash)
        family_key_id = intern_string(family_key) if family_key else -1
        record_key = (asset_key_id, target_hash_id, family_key_id)
        existing = record_ids.get(record_key)
        if existing is not None:
            return existing
        idx = len(records)
        records.append([asset_key_id, target_hash_id, family_key_id])
        record_ids[record_key] = idx
        return idx

    def append_unique_ref(mapping, key, record_id):
        existing = mapping.get(key)
        if existing is None:
            mapping[key] = record_id
            return
        if isinstance(existing, list):
            if record_id not in existing:
                existing.append(record_id)
            return
        if existing != record_id:
            mapping[key] = [existing, record_id]

    for i in range(len(entries)):
        primary_key_index = entries[i]['primary_key_index']
        internal_id_index = entries[i]['internal_id_index']
        raw_key = keys[primary_key_index] if primary_key_index < len(keys) else None
        internal_id = internal_ids[internal_id_index] if 0 <= internal_id_index < len(internal_ids) else None
        bundle_info = resolve_bundle_info(i)
        if not bundle_info:
            continue

        target_hash = bundle_info.get('bundle_name')
        if not target_hash:
            continue

        candidate_keys = []
        if isinstance(raw_key, str) and raw_key:
            candidate_keys.append(raw_key.lower())
        if isinstance(internal_id, str) and internal_id:
            lowered_internal = internal_id.lower()
            if lowered_internal not in candidate_keys:
                candidate_keys.append(lowered_internal)

        if not candidate_keys:
            continue

        for asset_key in candidate_keys:
            asset_base = Path(asset_key).name.lower()
            family_key = _extract_family_key(asset_base)
            record_id = intern_record(asset_key, target_hash, family_key)
            append_unique_ref(assets_by_exact, asset_key, record_id)

            base_aliases = _normalize_filename(asset_base)
            if family_key:
                base_aliases.append(family_key)

            char_prefab_match = re.fullmatch(r'illust_(char\d{6})_\d+\.prefab', asset_base, re.IGNORECASE)
            if char_prefab_match:
                char_id = char_prefab_match.group(1).lower()
                base_aliases.extend([
                    char_id,
                    f'{char_id}.skel',
                    f'{char_id}.skel.bytes',
                    f'{char_id}.atlas',
                    f'{char_id}.atlas.txt',
                ])

            dating_match = re.fullmatch(r'(illust_dating\d+)\.prefab', asset_base, re.IGNORECASE)
            if dating_match:
                dating_id = dating_match.group(1).lower()
                base_aliases.extend([
                    dating_id,
                    f'{dating_id}.skel',
                    f'{dating_id}.skel.bytes',
                    f'{dating_id}.atlas',
                    f'{dating_id}.atlas.txt',
                ])

            dating_vp_match = re.fullmatch(r'(vp_illust_dating\d+)\.asset', asset_base, re.IGNORECASE)
            if dating_vp_match:
                vp_key = dating_vp_match.group(1).lower()
                base_aliases.extend([
                    vp_key,
                    f'{vp_key}.skel',
                    f'{vp_key}.skel.bytes',
                    f'{vp_key}.atlas',
                    f'{vp_key}.atlas.txt',
                ])
                if vp_key.startswith('vp_'):
                    dating_id = vp_key[3:]
                    base_aliases.extend([
                        dating_id,
                        f'{dating_id}.skel',
                        f'{dating_id}.skel.bytes',
                        f'{dating_id}.atlas',
                        f'{dating_id}.atlas.txt',
                    ])

            for alias in dict.fromkeys(x.lower() for x in base_aliases if x):
                group_key = (alias, target_hash, family_key)
                candidate = base_candidates.get(group_key)
                score = _score_asset_key_for_base(asset_key, asset_base)
                if candidate is None or score > candidate[0]:
                    base_candidates[group_key] = (score, record_id)

    assets_by_base = {}
    for (asset_base, _target_hash, _family_key), (_score, record_id) in base_candidates.items():
        append_unique_ref(assets_by_base, asset_base, record_id)

    return {
        'schemaVersion': INDEX_SCHEMA_VERSION,
        'version': None,
        'strings': strings,
        'records': records,
        'assetsByExactKey': assets_by_exact,
        'assetsByBaseName': assets_by_base
    }


def load_or_build_asset_index(output_dir, quality, version, catalog_content):
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    _cleanup_stale_indexes(output_path, quality, version)

    index_path = output_path.joinpath(f"asset_index_{quality.lower()}_{version}.json")
    if index_path.exists():
        try:
            with open(index_path, 'r', encoding='utf-8') as f:
                index = json.load(f)
            if index.get('schemaVersion') == INDEX_SCHEMA_VERSION:
                return index
        except Exception:
            pass
        try:
            index_path.unlink()
        except Exception:
            pass

    index = build_asset_index(catalog_content)
    index['version'] = version
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False, separators=(',', ':'))
    return index


def normalize_filename(filename: str):
    return _normalize_filename(filename)
