# -*- coding: utf-8 -*-
"""
Resolves mod file names against the local bundle index.

This is a simplified replacement for the catalog-based resolver. Instead of
parsing catalog addressable keys and using complex bridge/prefab strategies,
it directly looks up asset names (m_Name) in the local bundle index.

The matching is straightforward:
  - Mod file "char000104.png" → looks up "char000104.png" in index
  - Mod file "char000104.json" → also tries "char000104.skel" (JSON→skel conversion)
  - Mod file "char000104.atlas.txt" → also tries "char000104.atlas"

The return format is kept compatible with the Kotlin layer.
"""
import re
from pathlib import Path


def resolve_mod_folder(mod_file_names, local_index):
    """
    Resolve a list of mod file names against the local bundle index.

    Finds which bundle(s) contain the target assets, then identifies
    the single common bundle that all mod files belong to.

    Uses the catalog-based authoritative mapping (catalogAssetToBundle)
    as primary lookup. Falls back to scan-based assetToBundles if the
    catalog doesn't have a mapping for a given asset.

    Args:
        mod_file_names: list of mod file paths/names
        local_index: dict with 'assetToBundles' and 'catalogAssetToBundle' keys

    Returns:
        A resolution result dict compatible with the Kotlin layer:
        {
            'targetHash': str or None (bundle name),
            'resolvedFamilyKey': str or None,
            'resolvedTargets': list of match dicts,
            'unresolvedFiles': list of unmatched file names,
            'resolutionState': 'KNOWN' | 'UNKNOWN' | 'INVALID',
            'errorReason': str or None
        }
    """
    asset_to_bundles = (local_index or {}).get("assetToBundles", {})
    catalog_asset_to_bundle = (local_index or {}).get("catalogAssetToBundle", {})

    unresolved = []
    file_matches = []

    for file_name in mod_file_names or []:
        base_name = Path(file_name).name
        candidates = _expand_candidates(base_name)

        matched_candidate = None
        matched_bundles = set()
        match_strategy = 'LOCAL_SCAN'

        for candidate in candidates:
            bundles = asset_to_bundles.get(candidate)
            if bundles:
                if matched_candidate is None:
                    matched_candidate = candidate
                matched_bundles.update(bundles)

        # Use catalog to narrow down if multiple bundles matched
        if len(matched_bundles) > 1:
            for candidate in candidates:
                catalog_bundle = catalog_asset_to_bundle.get(candidate)
                if catalog_bundle and catalog_bundle in matched_bundles:
                    matched_bundles = {catalog_bundle}
                    match_strategy = 'CATALOG_FILTERED'
                    break

        if matched_candidate and matched_bundles:
            file_matches.append({
                'fileName': base_name,
                'candidates': candidates,
                'candidate': matched_candidate,
                'bundles': matched_bundles,
                'matchStrategy': match_strategy,
            })
        else:
            unresolved.append(base_name)

    # No matches at all
    if not file_matches:
        return {
            'targetHash': None,
            'resolvedFamilyKey': None,
            'resolvedTargets': [],
            'unresolvedFiles': unresolved,
            'resolutionState': 'UNKNOWN',
            'errorReason': 'No matching bundle found in local index'
        }

    # Find common bundle across all matched files
    candidate_sets = [entry['bundles'] for entry in file_matches]

    intersection = set(candidate_sets[0])
    for s in candidate_sets[1:]:
        intersection &= s

    union = set()
    for s in candidate_sets:
        union |= s

    target_bundle = None
    if len(intersection) == 1:
        target_bundle = next(iter(intersection))
    elif len(intersection) > 1:
        # Multiple common bundles — pick alphabetically
        target_bundle = sorted(intersection)[0]
    elif len(union) == 1:
        # No strict intersection but only one bundle total
        target_bundle = next(iter(union))

    if target_bundle is None:
        # Files point to different bundles — can't determine a single target
        return {
            'targetHash': None,
            'resolvedFamilyKey': None,
            'resolvedTargets': [
                _build_target(entry) for entry in file_matches
            ],
            'unresolvedFiles': unresolved,
            'resolutionState': 'INVALID',
            'errorReason': 'Mod files map to different bundles'
        }

    resolved_targets = [
        _build_target(entry, target_bundle) for entry in file_matches
    ]
    family_key = _compute_family_key(file_matches)

    return {
        'targetHash': target_bundle,
        'resolvedFamilyKey': family_key,
        'resolvedTargets': resolved_targets,
        'unresolvedFiles': unresolved,
        'resolutionState': 'KNOWN',
        'errorReason': None
    }


def _expand_candidates(base_name):
    """
    Expand a mod filename to possible asset names in the bundle.

    Handles extension mappings:
      .json       → also try .skel    (mod JSON animation → bundle skel TextAsset)
      .skel.bytes → also try .skel    (catalog convention → actual m_Name)
      .atlas.txt  → also try .atlas   (catalog convention → actual m_Name)
    """
    lowered = (base_name or "").strip().lower()
    if not lowered:
        return []

    candidates = [lowered]

    # JSON animation → skel (user provides .json, bundle has .skel TextAsset)
    if lowered.endswith('.json'):
        candidates.append(lowered[:-5] + '.skel')

    # .skel.bytes → .skel (catalog uses .skel.bytes, bundle m_Name is .skel)
    if lowered.endswith('.skel.bytes'):
        candidates.append(lowered[:-6])  # strip '.bytes'

    # .atlas.txt → .atlas
    if lowered.endswith('.atlas.txt'):
        candidates.append(lowered[:-4])  # strip '.txt'

    return list(dict.fromkeys(candidates))


def _build_target(entry, target_bundle=None):
    """Build a resolved target dict for Kotlin compatibility."""
    bundle_name = target_bundle or (sorted(entry['bundles'])[0] if entry['bundles'] else None)
    family_key = _extract_stem(entry['fileName'])

    return {
        'originalFileName': entry['fileName'],
        'normalizedCandidates': entry.get('candidates', []),
        'resolvedAssetKey': entry['candidate'],
        'resolvedBundleName': bundle_name,
        'assetType': _infer_asset_type(entry['fileName']),
        'targetHash': bundle_name,
        'familyKey': family_key,
        'matchStrategy': entry.get('matchStrategy', 'LOCAL_SCAN'),
        'confidence': 1.0,
    }


def _infer_asset_type(file_name):
    """Infer the asset type from a mod file extension."""
    lowered = (file_name or "").lower()
    if lowered.endswith('.png'):
        return 'Texture2D'
    if lowered.endswith('.json'):
        return 'JsonSkeleton'
    if any(lowered.endswith(ext) for ext in ('.atlas', '.atlas.txt', '.skel', '.skel.txt', '.skel.bytes')):
        return 'TextAsset'
    return 'Unknown'


def _compute_family_key(file_matches):
    """
    Compute a family key from the matched files.
    The family key groups related assets (e.g., "char000104").
    """
    stems = set()
    for entry in file_matches:
        stem = _extract_stem(entry['fileName'])
        if stem:
            stems.add(stem)

    if len(stems) == 1:
        return next(iter(stems))
    return None


def _extract_stem(file_name):
    """
    Extract the base stem from a filename, removing extensions and numbered suffixes.
    Examples:
        "char000104.png"        → "char000104"
        "char000104_2.png"      → "char000104"
        "char000104.skel"       → "char000104"
        "char000104.skel.bytes" → "char000104"
        "cutscene_char061002.atlas" → "cutscene_char061002"
    """
    lowered = (file_name or "").strip().lower()
    if not lowered:
        return None

    # Remove known compound extensions first
    for ext in ('.skel.bytes', '.atlas.txt', '.skel.txt'):
        if lowered.endswith(ext):
            lowered = lowered[:-len(ext)]
            break
    else:
        lowered = Path(lowered).stem

    # Remove numbered suffix like _2, _3
    stem = re.sub(r'_\d+$', '', lowered)
    return stem if stem else None
