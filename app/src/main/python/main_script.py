# -*- coding: utf-8 -*-
"""Entry points called from Kotlin.

Everything asset-related is resolved from the game's current Addressables
catalog (see catalog_index.py).  There is no local bundle scanning any more.
"""
import json
import os
import sys
import threading
from pathlib import Path

sys.path.append(os.path.join(os.path.dirname(__file__), 'vendor'))

import catalog_index
import cdn_downloader
import character_scraper
import resolver
import spine_merger
from repacker.repacker import repack_bundle
from unpacker import unpack_bundle as unpacker_main

# In-memory state for the active catalog index.
_index_state = {
    'index': None,
    'quality': None,
    'version': None,
}
_index_lock = threading.Lock()


def _report(progress_callback, message):
    if progress_callback:
        progress_callback(message)
    print(message)


def _load_cached_index(output_dir, quality, version):
    index = catalog_index.load_catalog_index(output_dir, quality, version)
    if index is None:
        return None
    _index_state.update({'index': index, 'quality': quality, 'version': index.get('version')})
    return index


def _normalize_quality(quality):
    # The CDN only ships SD and HD catalogs; FHD uses the HD assets.
    return 'SD' if str(quality).upper() == 'SD' else 'HD'


def ensure_catalog_index(output_dir, quality='HD', progress_callback=None, force=False):
    """Load or (re)build the catalog index. Returns (index, error)."""
    with _index_lock:
        return _ensure_catalog_index(output_dir, quality, progress_callback, force)


def _ensure_catalog_index(output_dir, quality, progress_callback, force):
    quality = _normalize_quality(quality)
    if not force and _index_state['index'] is not None and _index_state['quality'] == quality:
        return _index_state['index'], None

    _report(progress_callback, 'Checking game bundle version...')
    version = cdn_downloader.get_cdn_version(quality)

    if not version:
        cached = _load_cached_index(output_dir, quality, None)
        if cached is not None:
            _report(progress_callback,
                    'Could not reach the game CDN; using the cached catalog index.')
            return cached, None
        return None, 'Failed to get the game bundle version and no cached index exists.'

    cached = _load_cached_index(output_dir, quality, version)
    if cached is not None and not force:
        _report(progress_callback, 'Catalog index is up to date ({}).'.format(version))
        return cached, None

    content, error = cdn_downloader.download_catalog(quality, version, progress_callback)
    if error:
        fallback = _load_cached_index(output_dir, quality, None)
        if fallback is not None:
            _report(progress_callback,
                    'Catalog download failed ({}); using cached index.'.format(error))
            return fallback, None
        return None, error

    _report(progress_callback, 'Building asset index...')
    try:
        index = catalog_index.build_catalog_index(content, version=version)
    except Exception as e:
        import traceback
        return None, 'Failed to build catalog index: {}\n{}'.format(e, traceback.format_exc())
    finally:
        del content

    catalog_index.cleanup_legacy_files(output_dir)
    try:
        catalog_index.save_catalog_index(output_dir, quality, index)
    except OSError as e:
        _report(progress_callback, 'Warning: could not cache the catalog index: {}'.format(e))

    _index_state.update({'index': index, 'quality': quality, 'version': version})
    _report(progress_callback,
            'Asset index ready: {} bundles.'.format(len(index.get('bundles') or {})))
    return index, None


# ---------------------------------------------------------------------------
# Character metadata
# ---------------------------------------------------------------------------

def update_character_data(output_dir, quality='HD'):
    """Refresh characters.json / bundle_hints.json. Returns (status, message)."""
    try:
        index, error = ensure_catalog_index(output_dir, quality)
        if error:
            return 'FAILED', error

        characters_path = Path(output_dir) / 'characters.json'
        stored_version = None
        if characters_path.exists():
            try:
                with open(characters_path, 'r', encoding='utf-8') as handle:
                    stored_version = json.load(handle).get('version')
            except (OSError, ValueError):
                stored_version = None

        if stored_version == index.get('version'):
            hints_path = Path(output_dir) / 'bundle_hints.json'
            if hints_path.exists():
                character_scraper.load_bundle_hints(output_dir)
                return 'SKIPPED', 'characters.json is already up to date.'

        success, message = character_scraper.scrape_and_save_from_index(
            output_dir, index.get('version'), index)
        if not success:
            return 'FAILED', message
        return 'SUCCESS', 'Character data refreshed for version {}.'.format(index.get('version'))
    except Exception:
        import traceback
        return 'FAILED', traceback.format_exc()


# ---------------------------------------------------------------------------
# Mod resolution
# ---------------------------------------------------------------------------

def _resolve_with_index(mods, output_dir, quality, progress_callback):
    index, error = ensure_catalog_index(output_dir, quality, progress_callback)
    if error:
        return None, error
    hints = character_scraper.load_bundle_hints(output_dir)
    results = []
    for mod in mods:
        results.append({
            'id': mod.get('id'),
            'result': resolver.resolve_mod_folder(mod.get('fileNames') or [], index, hints),
        })
    return results, None


def resolve_mod_files(file_names_json, output_dir, quality='HD', progress_callback=None):
    """Resolve one mod folder. Returns (success, json_result_or_error)."""
    try:
        file_names = json.loads(file_names_json) if isinstance(file_names_json, str) else file_names_json
        results, error = _resolve_with_index(
            [{'id': 0, 'fileNames': file_names}], output_dir, quality, progress_callback)
        if error:
            return False, error
        return True, json.dumps(results[0]['result'])
    except Exception:
        import traceback
        return False, traceback.format_exc()


def resolve_mod_batch(mods_json, output_dir, quality='HD', progress_callback=None):
    """Resolve a batch of mods. Returns (success, json_results_or_error)."""
    try:
        mods = json.loads(mods_json) if isinstance(mods_json, str) else mods_json
        results, error = _resolve_with_index(mods or [], output_dir, quality, progress_callback)
        if error:
            return False, error
        return True, json.dumps(results)
    except Exception:
        import traceback
        return False, traceback.format_exc()


# ---------------------------------------------------------------------------
# Bundle download
# ---------------------------------------------------------------------------

def download_bundle(hashed_name, quality, output_dir, cache_key=None, progress_callback=None, index_dir=None):
    """Download one bundle for repacking. Returns (success, path_or_error).

    ``index_dir`` is where the catalog index cache lives (app files dir);
    ``output_dir`` is the scratch directory the bundle bytes are written to.
    """
    try:
        quality = _normalize_quality(quality)
        index, error = ensure_catalog_index(index_dir or output_dir, quality, progress_callback)
        if error:
            return False, error
        path, error = cdn_downloader.download_bundle(
            index, hashed_name, quality, index.get('version'), output_dir, progress_callback)
        if error:
            return False, error
        return True, path
    except Exception:
        import traceback
        return False, traceback.format_exc()


# ---------------------------------------------------------------------------
# Unpack / repack / spine merge
# ---------------------------------------------------------------------------

def unpack_bundle(bundle_path, output_dir, progress_callback=None):
    """Unpack a game bundle. Returns (success, message)."""
    try:
        success, message = unpacker_main(
            bundle_path=bundle_path,
            output_dir=output_dir,
            progress_callback=progress_callback,
        )
        print(message)
        return success, message
    except Exception:
        import traceback
        error_message = traceback.format_exc()
        _report(progress_callback, 'An error occurred during unpack: {}'.format(error_message))
        return False, error_message


def main(original_bundle_path, modded_assets_folder, output_path, use_astc, progress_callback=None):
    """Repack a bundle with mod assets.

    Returns (success, message, unmatched_files_json).  ``unmatched_files_json``
    lists mod files that did not match any asset in the bundle, letting the
    caller retry with an alternative bundle if one is available.
    """
    try:
        success, message, unmatched = repack_bundle(
            original_bundle_path=original_bundle_path,
            modded_assets_folder=modded_assets_folder,
            output_path=output_path,
            use_astc=use_astc,
            progress_callback=progress_callback,
        )
        print(message)
        return success, message, json.dumps(unmatched or [])
    except Exception:
        import traceback
        error_message = traceback.format_exc()
        print('An error occurred: {}'.format(error_message))
        return False, error_message, json.dumps([])


def merge_spine_assets(mod_dir_path, progress_callback=None):
    """Run the spine atlas merger. Returns (success, message)."""
    def report_progress(message):
        _report(progress_callback, message)

    try:
        message = spine_merger.run(mod_dir_path, report_progress)
        report_progress(message)
        return True, message
    except Exception:
        import traceback
        error_message = traceback.format_exc()
        report_progress('An error occurred during spine merge: {}'.format(error_message))
        return False, error_message
