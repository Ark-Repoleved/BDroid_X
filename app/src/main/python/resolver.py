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
            matches.append(_build_match(base_name, candidates, exact_match, 'EXACT', 1.0))

        for candidate in candidates:
            hits = assets_by_base.get(candidate.lower()) or []
            hits = _prefer_primary_hits(hits)
            strategy = 'EXACT' if candidate.lower() == base_name.lower() else 'EXTENSION_MAPPING'
            confidence = 1.0 if strategy == 'EXACT' else 0.9
            for hit in hits:
                matches.append(_build_match(base_name, candidates, hit, strategy, confidence))

        matches = _dedupe_matches(matches)

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

    if len(family_keys) > 1:
        return {
            'targetHash': None,
            'resolvedFamilyKey': None,
            'resolvedTargets': resolved_targets,
            'unresolvedFiles': unresolved_files,
            'resolutionState': 'INVALID',
            'errorReason': 'Multiple targets detected in one mod folder'
        }

    return {
        'targetHash': target_hash,
        'resolvedFamilyKey': next(iter(family_keys)) if family_keys else None,
        'resolvedTargets': resolved_targets,
        'unresolvedFiles': unresolved_files,
        'resolutionState': 'KNOWN',
        'errorReason': None
    }


def _expand_candidates(base_name: str):
    return list(dict.fromkeys(normalize_filename(base_name)))


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
