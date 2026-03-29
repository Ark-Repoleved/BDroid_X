# -*- coding: utf-8 -*-
import re
from pathlib import Path

from catalog_indexer import normalize_filename


def resolve_mod_folder(mod_file_names, asset_index):
    resolved_targets = []
    unresolved_files = []
    target_hashes = set()
    family_keys = set()

    assets_by_base = (asset_index or {}).get('assetsByBaseName', {})
    assets_by_exact = (asset_index or {}).get('assetsByExactKey', {})

    for file_name in mod_file_names or []:
        base_name = Path(file_name).name
        lowered_full = (file_name or '').replace('\\', '/').lower()
        candidates = normalize_filename(base_name)
        matched = None
        strategy = 'NONE'

        if lowered_full in assets_by_exact:
            matched = assets_by_exact[lowered_full]
            strategy = 'EXACT'
        else:
            for candidate in candidates:
                hits = assets_by_base.get(candidate.lower()) or []
                if hits:
                    matched = hits[0]
                    strategy = 'EXACT' if candidate.lower() == base_name.lower() else 'EXTENSION_MAPPING'
                    break

        if matched:
            target_hash = matched.get('targetHash')
            family_key = _normalize_family_key(matched.get('familyKey'))
            if target_hash:
                target_hashes.add(target_hash)
            if family_key:
                family_keys.add(family_key)
            resolved_targets.append({
                'originalFileName': base_name,
                'normalizedCandidates': candidates,
                'resolvedAssetKey': matched.get('assetKey'),
                'resolvedBundleName': matched.get('bundleName'),
                'resolvedBundlePath': None,
                'assetType': _infer_asset_type(base_name),
                'targetHash': target_hash,
                'familyKey': family_key,
                'matchStrategy': strategy,
                'confidence': 1.0 if strategy == 'EXACT' else 0.9
            })
        else:
            unresolved_files.append(base_name)

    # INVALID should be based only on successfully resolved technical targets.
    # Unknown/unmatched files stay in unresolvedFiles and do not make the folder invalid by themselves.
    if len(target_hashes) > 1 or (len(target_hashes) == 1 and len(family_keys) > 1):
        state = 'INVALID'
        error_reason = 'Multiple targets detected in one mod folder'
        target_hash = None
        family_key = None
    elif len(target_hashes) == 1:
        target_hash = next(iter(target_hashes))
        family_key = next(iter(family_keys)) if family_keys else None
        state = 'KNOWN'
        error_reason = None
    else:
        target_hash = None
        family_key = None
        state = 'UNKNOWN'
        error_reason = 'No matching target could be resolved'

    return {
        'targetHash': target_hash,
        'resolvedFamilyKey': family_key,
        'resolvedTargets': resolved_targets,
        'unresolvedFiles': unresolved_files,
        'resolutionState': state,
        'errorReason': error_reason
    }


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
    lowered = re.sub(r'([_-])(\d+)$', '', lowered)
    return lowered
