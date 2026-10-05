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


def _basename(file_name):
    return (file_name or '').strip().replace('\\', '/').rsplit('/', 1)[-1].lower()


def lookup_stems(file_name):
    """Mod file name -> ordered lookup stems (exact first, variant stripped next)."""
    base = _basename(file_name)
    if not base:
        return []
    stem = asset_stem(base)
    stems = [stem]
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
