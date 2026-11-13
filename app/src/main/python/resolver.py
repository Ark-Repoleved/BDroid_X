# -*- coding: utf-8 -*-
"""
Resolves mod file names against the catalog index built by catalog_index.py.

Resolution is content-first: every catalog key that names the exact asset is a
candidate, and Spine families additionally get their prefab/playable bundle as
a candidate.  Bundles that cover more of the mod's files, with stronger
evidence (skeleton data > playable > prefab > artwork), win.

The result is compatible with the Kotlin layer and also carries
``candidateHashes``: a ranked list of fallback bundles that the installer can
try when a bundle turns out not to contain the mod's assets.
"""
from catalog_index import asset_stem, variant_stem, CENSOR_PENALTY

MAX_CANDIDATES = 4

STATE_KNOWN = 'KNOWN'
STATE_UNKNOWN = 'UNKNOWN'
STATE_INVALID = 'INVALID'

STRATEGY_EXACT = 'EXACT'
STRATEGY_FAMILY = 'NORMALIZED'
STRATEGY_HINT = 'FALLBACK'

_IGNORED_NAMES = {
    '.ds_store', 'thumbs.db', 'desktop.ini', '.modfile', '.nomedia',
    'mod.json', 'info.json', 'manifest.json', 'metadata.json', 'config.json',
    'readme.txt', 'readme.md', 'changelog.txt', 'changelog.md',
    'license.txt', 'license.md', 'credits.txt',
    'preview.png', 'cover.png', 'thumb.png', 'thumbnail.png',
}
_IGNORED_DIRS = {'__macosx', '.git', '.old'}
_IGNORED_EXTS = ('.modfile', '.md', '.jpg', '.jpeg', '.webp', '.gif', '.bmp', '.url', '.lnk')

# SpecialIllust_1 bundle stores several early CGs under sepcialillust_* addresses
# while the Texture2D m_Name inside the bundle is specialillust*.
SPECIAL_ILLUST_ADDRESS_TO_ASSET = {
    "sepcialillust_1": "specialillust3",
    "sepcialillust_6": "specialillust3-1",
    "sepcialillust_7": "specialillust11",
    "sepcialillust_8": "specialillust11-1",
    "sepcialillust_11": "specialillust5",
    "sepcialillust_15": "specialillust5-1",
    "sepcialillust_16": "specialillust5-2",
    "sepcialillust_19": "specialillust2",
    "sepcialillust_20": "specialillust4",
    "sepcialillust_21": "specialillust9",
    "sepcialillust_22": "specialillust10",
    "sepcialillust_23": "specialillust7",
    "sepcialillust_24": "specialillust6",
    "sepcialillust_25": "specialillust8",
    "sepcialillust_26_1": "specialillust4-1",
    "sepcialillust_26_2": "specialillust4-2",
    "sepcialillust_26_3": "specialillust4-3",
    "sepcialillust_26_4": "specialillust4-4",
    "sepcialillust_27": "specialillust14",
    "sepcialillust_28": "specialillust14-1",
    "sepcialillust_29": "specialillust15",
    "sepcialillust_30": "specialillust16",
    "sepcialillust_31_1": "specialillust17",
    "sepcialillust_31_3": "specialillust17-1",
    "sepcialillust_32_1": "specialillust17-2",
    "sepcialillust_32_2": "specialillust17-3",
    "sepcialillust_33": "specialillust18",
    "sepcialillust_34": "specialillust19",
    "sepcialillust_34_1": "specialillust19-1",
    "sepcialillust_35_1": "specialillust20",
    "sepcialillust_35_2": "specialillust20-1",
    "sepcialillust_35_3": "specialillust20-2",
    "sepcialillust_36_4": "specialillust21-1",
    "sepcialillust_37": "specialillust22-3",
    "sepcialillust_37_1": "specialillust22-5",
    "sepcialillust_37_2": "specialillust22-1",
    "sepcialillust_37_6": "specialillust22-4",
    "sepcialillust_38": "specialillust23",
    "sepcialillust_39": "specialillust24",
    "sepcialillust_40": "specialillust25",
    "sepcialillust_40_2": "specialillust25-1",
    "sepcialillust_41": "specialillust41",
    "sepcialillust_41_1": "specialillust41-1",
    "sepcialillust_42": "specialillust42",
    "sepcialillust_42_2": "specialillust42-1",
    "sepcialillust_42_3": "specialillust42-2",
    "sepcialillust_42_5": "specialillust42-5",
    "sepcialillust_42_6": "specialillust42-4",
    "sepcialillust_43": "specialillust43",
    "sepcialillust_43_1": "specialillust43-1",
    "sepcialillust_43_2": "specialillust43-2",
    "sepcialillust_43_3": "specialillust43-3",
    "sepcialillust_44": "specialillust51",
    "sepcialillust_45": "specialillust52",
    "sepcialillust_45-1": "specialillust52-1",
    "specialillust_46": "specialillust64",
    "sepcialillust_50-1": "specialillust60",
    "sepcialillust_95": "specialillust33",
    "sepcialillust_95-2": "specialillust34",
    "sepcialillust_95-3": "specialillust35",
    "sepcialillust_98": "specialillust26-1",
    "sepcialillust_98_1": "specialillust26",
    "sepcialillust_99_1": "specialillust28",
    "sepcialillust_99_2": "specialillust31-1",
    "sepcialillust_99_3": "specialillust30",
    "sepcialillust_99_4": "specialillust40",
    "sepcialillust_99_5": "specialillust31",
    "sepcialillust_99_6": "specialillust29",
    "sepcialillust_100": "specialillust44-4",
    "sepcialillust_100_1": "specialillust44-2",
    "sepcialillust_100_2": "specialillust44-3",
    "sepcialillust_100_3": "specialillust44",
    "sepcialillust_100_4": "specialillust44-1",
    "sepcialillust_101": "specialillust45",
    "sepcialillust_102": "specialillust46",
    "sepcialillust_103": "specialillust47",
    "sepcialillust_103_1": "specialillust48",
    "sepcialillust_104": "specialillust50",
    "sepcialillust_105": "specialillust53",
    "sepcialillust_105_1": "specialillust53-1",
    "sepcialillust_105_2": "specialillust53-2",
    "sepcialillust_105_3": "specialillust53-3",
    "sepcialillust_105_4": "specialillust53-4",
    "sepcialillust_105_5": "specialillust53-5",
    "sepcialillust_105_6": "specialillust53-6",
    "sepcialillust_106": "specialillust54",
    "sepcialillust_106_1": "specialillust56",
    "sepcialillust_106_3": "specialillust54-1",
    "sepcialillust_107_1": "specialillust57",
    "sepcialillust_107_2": "specialillust58",
    "sepcialillust_108": "specialillust61",
    "sepcialillust_108 1": "specialillust62",
    "sepcialillust_109": "specialillust63",
}
SPECIAL_ILLUST_ASSET_TO_ADDRESS = {v: k for k, v in SPECIAL_ILLUST_ADDRESS_TO_ASSET.items()}


def _is_ignored(file_name):
    norm = (file_name or '').strip().replace('\\', '/')
    if not norm:
        return True
    parts = [p for p in norm.split('/') if p]
    if not parts:
        return True
    if any(p.lower() in _IGNORED_DIRS for p in parts[:-1]):
        return True
    base = parts[-1].lower()
    if base in _IGNORED_NAMES or base in _IGNORED_DIRS or base.startswith('._'):
        return True
    if any(base.endswith(ext) for ext in _IGNORED_EXTS):
        return True
    if base.startswith('readme') and base.endswith(('.txt', '.md')):
        return True
    return False


def _basename(file_name):
    return (file_name or '').strip().replace('\\', '/').rsplit('/', 1)[-1].lower()


def lookup_stems(file_name):
    """Mod file name -> ordered lookup stems (exact first, variant/alias next)."""
    base = _basename(file_name)
    if not base:
        return []
    stem = asset_stem(base)
    stems = [stem]
    mapped_asset = SPECIAL_ILLUST_ADDRESS_TO_ASSET.get(stem)
    if mapped_asset:
        stems.append(mapped_asset)
    mapped_addr = SPECIAL_ILLUST_ASSET_TO_ADDRESS.get(stem)
    if mapped_addr:
        stems.append(mapped_addr)
    base_variant = variant_stem(stem)
    if base_variant and base_variant != stem:
        stems.append(base_variant)
    return list(dict.fromkeys(s for s in stems if s))


def _infer_asset_type(file_name):
    lowered = _basename(file_name)
    if lowered.endswith('.png'):
        return 'Texture2D'
    if lowered.endswith('.json'):
        return 'JsonSkeleton'
    if lowered.endswith(('.skel', '.skel.txt', '.skel.bytes')):
        return 'TextAsset'
    if lowered.endswith(('.atlas', '.atlas.txt')):
        return 'TextAsset'
    return 'TextAsset'


def _collect_candidates(stems, index, hints):
    """Return {bundle: (score, strategy)} for one mod file."""
    exact = index.get('exact', {})
    family = index.get('family', {})
    bundles = index.get('bundles', {})

    candidates = {}
    for rank, stem in enumerate(stems):
        for source, strategy in ((exact, STRATEGY_EXACT), (family, STRATEGY_FAMILY)):
            for bundle_name, score in (source.get(stem) or {}).items():
                weight = score - rank
                current = candidates.get(bundle_name)
                if current is None or weight > current[0]:
                    candidates[bundle_name] = (weight, strategy)
    if candidates:
        return candidates, stems[0]

    for stem in stems:
        hint = (hints or {}).get(stem)
        if hint and hint in bundles:
            return {hint: (1, STRATEGY_HINT)}, stem
    return {}, (stems[0] if stems else '')


def resolve_mod_folder(mod_file_names, index, hints=None):
    """Resolve a mod folder's files to a single target bundle.

    Returns a Kotlin-compatible dict; ``candidateHashes`` holds ranked
    fallback bundles for install-time retries.
    """
    index = index or {}
    censored = index.get('censored', {})

    matches = []
    unresolved = []
    for file_name in (mod_file_names or []):
        if _is_ignored(file_name):
            continue
        stems = lookup_stems(file_name)
        if not stems:
            continue
        candidates, anchor = _collect_candidates(stems, index, hints)
        censored_bundles = set(censored.get(anchor, ()))
        if not candidates:
            unresolved.append(_basename(file_name))
            continue
        matches.append({
            'fileName': _basename(file_name),
            'stem': anchor,
            'familyKey': variant_stem(anchor),
            'assetType': _infer_asset_type(file_name),
            'candidates': candidates,
            'censored': censored_bundles,
        })

    if not matches:
        return _result(
            target=None, candidate_hashes=[], unresolved=unresolved, targets=[],
            error='No matching bundle found in catalog',
            unresolved_files=unresolved,
        )

    total_files = len(matches)
    scores = {}
    for match in matches:
        for bundle_name, (weight, _strategy) in match['candidates'].items():
            entry = scores.setdefault(bundle_name, {'covered': 0, 'score': 0, 'censored': False})
            entry['covered'] += 1
            entry['score'] += weight
            if bundle_name in match['censored']:
                entry['censored'] = True

    ranked = sorted(
        scores.items(),
        key=lambda kv: (
            -kv[1]['covered'],
            -kv[1]['score'],
            kv[1]['censored'],
            kv[0],
        ),
    )

    best_bundle, best = ranked[0]
    candidate_hashes = [name for name, _ in ranked[:MAX_CANDIDATES]]

    uncovered = [m['fileName'] for m in matches if best_bundle not in m['candidates']]
    unresolved_files = unresolved + uncovered

    families = {m['familyKey'] for m in matches if m['familyKey']}
    family_key = next(iter(families)) if len(families) == 1 else None

    targets = []
    for match in matches:
        candidate = match['candidates'].get(best_bundle)
        strategy = candidate[1] if candidate else STRATEGY_HINT
        targets.append({
            'originalFileName': match['fileName'],
            'resolvedAssetKey': match['stem'],
            'resolvedBundleName': best_bundle,
            'targetHash': best_bundle,
            'familyKey': match['familyKey'],
            'assetType': match['assetType'],
            'matchStrategy': strategy,
            'confidence': 1.0 if candidate else 0.0,
        })

    if best['covered'] < total_files:
        return _result(
            target=None, candidate_hashes=candidate_hashes, unresolved=unresolved,
            targets=targets, error='Mod files map to different bundles',
            unresolved_files=unresolved_files,
        )

    return _result(
        target=best_bundle, candidate_hashes=candidate_hashes, unresolved=unresolved,
        targets=targets, error=None, family_key=family_key,
        unresolved_files=unresolved_files,
    )


def _result(target, candidate_hashes, unresolved, targets, error,
            family_key=None, unresolved_files=None):
    if target:
        state = STATE_KNOWN
    elif targets or unresolved:
        state = STATE_INVALID if targets else STATE_UNKNOWN
    else:
        state = STATE_UNKNOWN
    return {
        'targetHash': target,
        'resolvedFamilyKey': family_key,
        'resolvedTargets': targets,
        'unresolvedFiles': unresolved_files if unresolved_files is not None else unresolved,
        'candidateHashes': candidate_hashes,
        'resolutionState': state,
        'errorReason': error,
    }
