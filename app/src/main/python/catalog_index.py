# -*- coding: utf-8 -*-
"""
Compact asset -> bundle index built from the game's Addressables catalog.

The catalog is the single authoritative, always-current source for mod
resolution.  It lists every *addressable* asset together with the bundle that
contains it (name, content hash, CDN path).  Textures/skeletons of Spine
illustration families are embedded in the bundle of their prefab/playable
object, so we bridge from those stems to the mod file stems:

    illust_char000104_69.prefab   -> char000104          (mod: char000104.*)
    illust_npc000004_1.prefab     -> npc000004
    Illust_dating16.prefab        -> illust_dating16
    illust_talk5.prefab           -> illust_talk5
    SpecialIllust130.playable     -> specialillust130
    CutScene000101.prefab         -> cutscene_char000101

Everything else is matched by the exact asset file stem.

The resulting index is a few MB of JSON (vs. a 60+ MB catalog) and is rebuilt
automatically whenever the game's bundle version changes.
"""
import base64
import json
import re
import struct
from pathlib import Path

INDEX_SCHEMA_VERSION = 1
BUNDLE_PROVIDER = "UnityEngine.ResourceManagement.ResourceProviders.AssetBundleProvider"

_COMPOUND_EXTS = ('.skel.bytes', '.atlas.txt', '.skel.txt')
_EXACT_EXTS = (
    '.png', '.skel.bytes', '.skel.txt', '.atlas.txt',
    '.skel', '.atlas', '.bytes', '.txt', '.json',
)

# Candidate scores.  Higher = stronger evidence that the bundle holds the
# actual spine content referenced by the mod file.
SCORE_SKELETON = 10    # exact .skel.bytes / .atlas.txt keys (cutscene data)
SCORE_ASSET = 5        # exact Texture2D / TextAsset keys
SCORE_PLAYABLE = 8     # family alias from a .playable timeline
SCORE_PREFAB = 6       # family alias from a .prefab
SCORE_FLAT_IMAGE = 2   # standalone artwork (Illust_1/...), not spine content
SCORE_OTHER = 3        # other family alias sources
CENSOR_PENALTY = 1000

_FLAT_IMAGE_DIRS = ('illust_1/', 'specialillust_1/', 'idcardicon_1/', 'lobbysettingthumb_1/')


def read_int32(data, offset):
    return struct.unpack_from('<i', data, offset)[0]


def read_object(key_data, data_index):
    """Decode one serialized catalog key / extra-data object."""
    try:
        object_type = key_data[data_index]
        data_index += 1

        if object_type == 0:  # AsciiString
            num = read_int32(key_data, data_index)
            data_index += 4
            return key_data[data_index:data_index + num].decode('ascii')

        if object_type == 7:  # JsonObject
            num3 = key_data[data_index]
            data_index += 1
            data_index += num3
            num4 = key_data[data_index]
            data_index += 1
            data_index += num4
            num5 = read_int32(key_data, data_index)
            data_index += 4
            json_data = key_data[data_index:data_index + num5].decode('utf-16')
            return json.loads(json_data)

        return None
    except Exception:
        return None


def asset_stem(name):
    """Lowercase asset/mod stem with compound extensions removed."""
    lowered = (name or '').strip().lower()
    if not lowered:
        return ''
    for ext in _COMPOUND_EXTS:
        if lowered.endswith(ext):
            return lowered[:-len(ext)]
    dot = lowered.rfind('.')
    if dot > 0:
        return lowered[:dot]
    return lowered


def variant_stem(stem):
    """Strip a trailing _<number> texture variant index."""
    return re.sub(r'_\d+$', '', stem or '')


def family_aliases(stem):
    """Canonical mod-family stems implied by a catalog asset stem."""
    stem = (stem or '').lower()
    out = []
    for candidate in (stem, variant_stem(stem)):
        if not candidate:
            continue
        m = re.fullmatch(r'illust_char(\d{6})', candidate)
        if m:
            out.append('char' + m.group(1))
        m = re.fullmatch(r'illust_npc(\w+)', candidate)
        if m:
            out.append('npc' + m.group(1))
        m = re.fullmatch(r'illust_dating(\w+)', candidate)
        if m:
            out.append('illust_dating' + m.group(1))
        m = re.fullmatch(r'illust_talk(\w+)', candidate)
        if m:
            out.append('illust_talk' + m.group(1))
        m = re.fullmatch(r'(?:specialillust|sepcialillust|illustspecial)(\w+)', candidate)
        if m:
            out.append('specialillust' + m.group(1))
        m = re.fullmatch(r'cutscene(\d\w*?)(?:_loop(?:_\d+)?)?', candidate)
        if m:
            out.append('cutscene_char' + m.group(1))
            out.append('cutscene' + m.group(1))
        m = re.fullmatch(r'cutscene_char([0-9a-z]+)', candidate)
        if m:
            out.append('cutscene_char' + m.group(1))
    return list(dict.fromkeys(x for x in out if x))


def _source_score(key, basename):
    lowered = key.lower()
    if basename.endswith('.playable'):
        return SCORE_PLAYABLE
    if basename.endswith('.prefab'):
        return SCORE_PREFAB
    if any(lowered.startswith(d) for d in _FLAT_IMAGE_DIRS):
        return SCORE_FLAT_IMAGE
    if basename.endswith(('.skel.bytes', '.atlas.txt')):
        return SCORE_SKELETON
    return SCORE_ASSET


def _is_exact_candidate(basename):
    if basename.endswith('.prefab'):
        return False
    if basename.endswith(('.playable', '.asset', '.controller', '.anim', '.mat')):
        return False
    return basename.endswith(_EXACT_EXTS)


def _add_candidate(mapping, stem, bundle_name, score, censor=None):
    if not stem or not bundle_name:
        return
    scores = mapping.get(stem)
    if scores is None:
        scores = {}
        mapping[stem] = scores
    if score > scores.get(bundle_name, 0):
        scores[bundle_name] = score
    if censor is not None:
        censor.setdefault(stem, set()).add(bundle_name)


def build_catalog_index(catalog_content, version=None):
    """Parse an Addressables catalog and build the compact lookup index."""
    provider_ids = catalog_content.get('m_ProviderIds', [])
    bundle_provider_index = (
        provider_ids.index(BUNDLE_PROVIDER) if BUNDLE_PROVIDER in provider_ids else -1
    )

    bucket_array = base64.b64decode(catalog_content['m_BucketDataString'])
    key_array = base64.b64decode(catalog_content['m_KeyDataString'])
    extra_data = base64.b64decode(catalog_content['m_ExtraDataString'])
    entry_data = base64.b64decode(catalog_content['m_EntryDataString'])
    internal_ids = catalog_content.get('m_InternalIds') or []

    num_buckets = read_int32(bucket_array, 0)
    dependency_map = [None] * num_buckets
    data_offsets = []
    index = 4
    for i in range(num_buckets):
        data_offsets.append(read_int32(bucket_array, index))
        index += 4
        num_entries = read_int32(bucket_array, index)
        index += 4
        deps = []
        for _ in range(num_entries):
            deps.append(read_int32(bucket_array, index))
            index += 4
        dependency_map[i] = deps

    keys = [read_object(key_array, offset) for offset in data_offsets]

    number_of_entries = read_int32(entry_data, 0)
    index = 4
    bundles = {}   # entry index -> bundle record
    entries = []   # (internal_id_index, dependency_key_index, primary_key_index)

    for m in range(number_of_entries):
        internal_id_index = read_int32(entry_data, index)
        index += 4
        provider_index = read_int32(entry_data, index)
        index += 4
        dependency_key_index = read_int32(entry_data, index)
        index += 4
        index += 4  # dependency_hash
        data_index = read_int32(entry_data, index)
        index += 4
        primary_key_index = read_int32(entry_data, index)
        index += 4
        index += 4  # resource_type

        entries.append((internal_id_index, dependency_key_index, primary_key_index))

        if provider_index == bundle_provider_index and data_index >= 0:
            info = read_object(extra_data, data_index)
            if info and info.get('m_BundleName'):
                bundles[m] = {
                    'name': str(info['m_BundleName']),
                    'hash': str(info.get('m_Hash') or ''),
                    'size': int(info.get('m_BundleSize') or 0),
                    'internal_id': (
                        internal_ids[internal_id_index]
                        if 0 <= internal_id_index < len(internal_ids) else ''
                    ),
                    'download_name': (
                        str(keys[primary_key_index])
                        if 0 <= primary_key_index < len(keys)
                        and isinstance(keys[primary_key_index], str) else ''
                    ),
                }

    def resolve_bundle(entry_index):
        if entry_index in bundles:
            return bundles[entry_index]
        if entry_index < 0 or entry_index >= len(entries):
            return None
        dep_idx = entries[entry_index][1]
        if dep_idx < 0 or dep_idx >= len(dependency_map):
            return None
        for dep_entry in (dependency_map[dep_idx] or []):
            if dep_entry in bundles:
                return bundles[dep_entry]
        return None

    exact = {}
    family = {}
    censored = {}
    bundle_names = {}

    for i in range(len(entries)):
        if i in bundles:
            continue
        _, _, primary_key_index = entries[i]
        raw_key = keys[primary_key_index] if 0 <= primary_key_index < len(keys) else None
        if not isinstance(raw_key, str) or not raw_key:
            continue
        info = resolve_bundle(i)
        if not info:
            continue
        bundle_name = info['name']
        bundle_names[bundle_name] = info

        basename = raw_key.rsplit('/', 1)[-1].lower()
        stem = asset_stem(basename)
        if not stem:
            continue

        is_censored = 'censorship' in raw_key.lower()
        censor_target = censored if is_censored else None
        score = _source_score(raw_key, basename)

        if _is_exact_candidate(basename):
            _add_candidate(exact, stem, bundle_name, score, censor_target)
            base = variant_stem(stem)
            if base and base != stem:
                _add_candidate(exact, base, bundle_name, max(1, score - 2), censor_target)
        elif basename.endswith(('.prefab', '.playable', '.asset')):
            for alias in family_aliases(stem):
                _add_candidate(family, alias, bundle_name, score, censor_target)

    return {
        'schemaVersion': INDEX_SCHEMA_VERSION,
        'version': version,
        'bundles': bundle_names,
        'exact': exact,
        'family': family,
        'censored': {k: sorted(v) for k, v in censored.items()},
    }


def index_filename(quality):
    return 'catalog_index_{}.json'.format((quality or 'HD').lower())


def cleanup_legacy_files(output_dir):
    """Remove index artifacts written by older app versions."""
    try:
        root = Path(output_dir)
    except Exception:
        return
    for pattern in ('catalog_*.json', 'local_bundle_index.json', 'asset_index_*.json'):
        for path in root.glob(pattern):
            try:
                path.unlink()
            except OSError:
                pass


def load_catalog_index(output_dir, quality, version=None):
    """Load the cached index if it matches the requested version and schema."""
    path = Path(output_dir) / index_filename(quality)
    try:
        with open(path, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except (OSError, ValueError):
        return None
    if data.get('schemaVersion') != INDEX_SCHEMA_VERSION:
        return None
    if version is not None and data.get('version') != version:
        return None
    return data


def save_catalog_index(output_dir, quality, index):
    root = Path(output_dir)
    root.mkdir(parents=True, exist_ok=True)
    path = root / index_filename(quality)
    tmp_path = path.with_suffix('.tmp')
    with open(tmp_path, 'w', encoding='utf-8') as f:
        json.dump(index, f, ensure_ascii=False, separators=(',', ':'))
    tmp_path.replace(path)
    return str(path)


def get_bundle(index, bundle_name):
    """Look up the CDN record for a bundle name."""
    return ((index or {}).get('bundles') or {}).get(bundle_name)

