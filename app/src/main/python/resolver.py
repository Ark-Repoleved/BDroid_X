# -*- coding: utf-8 -*-
import re
from pathlib import Path

from catalog_indexer import normalize_filename


def resolve_mod_folder(mod_file_names, asset_index):
    unresolved_files = []
    file_matches = []

    assets_by_base = (asset_index or {}).get('assetsByBaseName', {})
    assets_by_exact = (asset_index or {}).get('assetsByExactKey', {})

    for file_name in mod_file_names or []:
        base_name = Path(file_name).name
        lowered_full = (file_name or '').replace('\\', '/').lower()
        candidates = _expand_candidates(base_name)
        matches = []

        exact_match = assets_by_exact.get(lowered_full)
        if exact_match:
            exact_hits = _decode_hits(asset_index, exact_match)
            exact_hits = _prefer_primary_hits(exact_hits)
            for hit in exact_hits:
                matches.append(_build_match(base_name, candidates, hit, 'EXACT', 1.0))

        for candidate in candidates:
            hits = assets_by_base.get(candidate.lower()) or []
            hits = _decode_hits(asset_index, hits)
            hits = _prefer_primary_hits(hits)
            strategy = 'EXACT' if candidate.lower() == base_name.lower() else 'EXTENSION_MAPPING'
            confidence = 1.0 if strategy == 'EXACT' else 0.9
            for hit in hits:
                matches.append(_build_match(base_name, candidates, hit, strategy, confidence))

        matches = _dedupe_matches(matches)
        matches = _filter_bridge_noise(base_name, matches)

        if matches:
            file_matches.append({
                'fileName': base_name,
                'candidates': candidates,
                'matches': matches,
                'targetHashes': {m.get('targetHash') for m in matches if m.get('targetHash')}
            })
        else:
            unresolved_files.append(base_name)

    candidate_sets = [entry['targetHashes'] for entry in file_matches if entry['targetHashes']]

    if not candidate_sets:
        return {
            'targetHash': None,
            'resolvedFamilyKey': None,
            'resolvedTargets': [],
            'unresolvedFiles': unresolved_files,
            'resolutionState': 'UNKNOWN',
            'errorReason': 'No matching target could be resolved'
        }

    intersection = set(candidate_sets[0])
    for target_set in candidate_sets[1:]:
        intersection &= target_set

    union = set()
    for target_set in candidate_sets:
        union |= target_set

    if len(intersection) == 1:
        target_hash = next(iter(intersection))
    elif len(union) == 1:
        target_hash = next(iter(union))
    else:
        return {
            'targetHash': None,
            'resolvedFamilyKey': None,
            'resolvedTargets': _select_representative_matches(file_matches, None),
            'unresolvedFiles': unresolved_files,
            'resolutionState': 'INVALID',
            'errorReason': 'Multiple targets detected in one mod folder'
        }

    resolved_targets = _select_representative_matches(file_matches, target_hash)
    family_keys = {
        _normalize_family_key(match.get('familyKey'))
        for match in resolved_targets
        if _normalize_family_key(match.get('familyKey'))
    }

    return {
        'targetHash': target_hash,
        'resolvedFamilyKey': next(iter(family_keys)) if len(family_keys) == 1 else None,
        'resolvedTargets': resolved_targets,
        'unresolvedFiles': unresolved_files,
        'resolutionState': 'KNOWN',
        'errorReason': None
    }


def _expand_candidates(base_name: str):
    candidates = list(dict.fromkeys(normalize_filename(base_name)))

    lowered_base = (base_name or '').strip().lower()
    stem = _mod_asset_stem(lowered_base)
    if stem:
        if re.fullmatch(r'illust_dating\d+', stem, re.IGNORECASE):
            candidates.extend([
                f'{stem}.prefab',
                f'vp_{stem}.asset',
                f'char/datingillust/{stem}.prefab',
                f'char/datingillust/vp_{stem}.asset',
            ])

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

    bridge_key = _extract_sactx_bridge_key(base_name)
    if bridge_key:
        lowered = bridge_key.lower()
        candidates.extend([
            lowered,
            f"{lowered}.spriteatlasv2"
        ])

    return list(dict.fromkeys(candidates))


def _mod_asset_stem(asset_name: str):
    lowered = (asset_name or '').strip().lower()
    if not lowered:
        return None

    for suffix in ('.skel.bytes', '.atlas.txt'):
        if lowered.endswith(suffix):
            return lowered[:-len(suffix)]

    return Path(lowered).stem



def _extract_sactx_bridge_key(file_name: str):
    lowered = (file_name or '').strip().lower()
    if not lowered.startswith('sactx-'):
        return None

    stem = Path(lowered).stem
    match = re.search(r'-(localpacktitle\d+_[a-z0-9]+)-[0-9a-f]{6,}$', stem, re.IGNORECASE)
    if not match:
        return None

    return match.group(1)


def _decode_hits(asset_index, raw_hits):
    if raw_hits is None:
        return []
    if not isinstance(raw_hits, list):
        raw_hits = [raw_hits]

    decoded = []
    for item in raw_hits:
        hit = _decode_hit(asset_index, item)
        if hit:
            decoded.append(hit)
    return decoded


def _decode_hit(asset_index, raw_hit):
    if isinstance(raw_hit, dict):
        return raw_hit
    if not isinstance(raw_hit, int):
        return None

    records = (asset_index or {}).get('records') or []
    strings = (asset_index or {}).get('strings') or []
    if raw_hit < 0 or raw_hit >= len(records):
        return None

    record = records[raw_hit]
    if not isinstance(record, list) or len(record) < 3:
        return None

    asset_key = _string_at(strings, record[0])
    target_hash = _string_at(strings, record[1])
    family_key = _string_at(strings, record[2])

    return {
        'assetKey': asset_key,
        'bundleName': None,
        'targetHash': target_hash,
        'familyKey': family_key
    }


def _string_at(strings, index):
    if index is None or index < 0 or index >= len(strings):
        return None
    return strings[index]


def _prefer_primary_hits(hits):
    if not hits:
        return []

    primary_hits = [hit for hit in hits if '/censorship/' not in (hit.get('assetKey') or '')]
    return primary_hits if primary_hits else hits


def _build_match(base_name: str, candidates, matched, strategy: str, confidence: float):
    target_hash = matched.get('targetHash')
    family_key = _normalize_family_key(matched.get('familyKey'))
    return {
        'originalFileName': base_name,
        'normalizedCandidates': candidates,
        'resolvedAssetKey': matched.get('assetKey'),
        'resolvedBundleName': matched.get('bundleName'),
        'resolvedBundlePath': None,
        'assetType': _infer_asset_type(base_name),
        'targetHash': target_hash,
        'familyKey': family_key,
        'matchStrategy': strategy,
        'confidence': confidence
    }


def _dedupe_matches(matches):
    deduped = []
    seen = set()
    for match in matches:
        key = (
            match.get('resolvedAssetKey'),
            match.get('targetHash'),
            match.get('familyKey'),
            match.get('matchStrategy')
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(match)
    return deduped


def _filter_bridge_noise(base_name: str, matches):
    if not matches:
        return matches

    lowered = (base_name or '').strip().lower()
    if not (lowered.endswith('.skel') or lowered.endswith('.skel.bytes') or lowered.endswith('.atlas') or lowered.endswith('.atlas.txt')):
        return matches

    stem = _mod_asset_stem(lowered)
    if not stem:
        return matches

    preferred = []

    if re.fullmatch(r'char\d{6}', stem, re.IGNORECASE):
        pattern = re.compile(rf'(^|.*/)illust_{re.escape(stem)}_\d+\.prefab$', re.IGNORECASE)
        for match in matches:
            asset_key = (match.get('resolvedAssetKey') or '').lower()
            if pattern.search(asset_key):
                preferred.append(match)
        return preferred if preferred else matches

    if re.fullmatch(r'npc\d{6}', stem, re.IGNORECASE):
        pattern = re.compile(rf'(^|.*/)illust_{re.escape(stem)}_\d+\.prefab$', re.IGNORECASE)
        for match in matches:
            asset_key = (match.get('resolvedAssetKey') or '').lower()
            if pattern.search(asset_key):
                preferred.append(match)
        return preferred if preferred else matches

    return matches


def _select_representative_matches(file_matches, target_hash):
    resolved_targets = []
    for entry in file_matches:
        matches = entry['matches']
        chosen = None

        if target_hash:
            matching_target = [m for m in matches if m.get('targetHash') == target_hash]
            chosen = _prefer_best_match(matching_target)
        else:
            chosen = _prefer_best_match(matches)

        if chosen:
            resolved_targets.append(chosen)

    return resolved_targets


def _prefer_best_match(matches):
    if not matches:
        return None

    strategy_rank = {
        'EXACT': 0,
        'EXTENSION_MAPPING': 1,
        'NONE': 2
    }

    return sorted(
        matches,
        key=lambda m: (
            strategy_rank.get(m.get('matchStrategy'), 99),
            m.get('resolvedAssetKey') or ''
        )
    )[0]


def _infer_asset_type(file_name: str):
    lowered = (file_name or '').lower()
    if lowered.endswith('.png'):
        return 'Texture2D'
    if lowered.endswith('.atlas') or lowered.endswith('.atlas.txt') or lowered.endswith('.skel') or lowered.endswith('.skel.txt') or lowered.endswith('.skel.bytes'):
        return 'TextAsset'
    if lowered.endswith('.json'):
        return 'JsonSkeleton'
    return 'Unknown'


def _normalize_family_key(value: str):
    if not value:
        return None
    lowered = value.lower()
    lowered = re.sub(r'_(\d+)$', '', lowered)
    return lowered
