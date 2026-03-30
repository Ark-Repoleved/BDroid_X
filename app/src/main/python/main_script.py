import sys
import os
import threading

# Add the vendor directory to the sys.path to allow importing bundled packages
sys.path.append(os.path.join(os.path.dirname(__file__), "vendor"))

from repacker.repacker import repack_bundle
import character_scraper
import cdn_downloader
from unpacker import unpack_bundle as unpacker_main
import spine_merger
import resolver
import catalog_indexer
import json
from pathlib import Path

# --- Global Cache for CDN Catalog ---
# In-memory cache for the catalog JSON content.
# The key is the version, the value is the parsed JSON content.
catalog_cache = {}
# A lock to ensure thread-safe access to the cache.
catalog_cache_lock = threading.Lock()
# A variable to track the current cache key (e.g., a timestamp for the batch install)
# to ensure that different installation processes use different caches.
current_cache_key = None


def _prune_catalog_cache(keep_version=None):
    stale_versions = [version for version in catalog_cache.keys() if version != keep_version]
    for version in stale_versions:
        catalog_cache.pop(version, None)


def _load_valid_local_index(output_dir: str, quality: str, version: str = None):
    output_path = Path(output_dir)
    pattern = f"asset_index_{quality.lower()}_*.json"
    candidates = sorted(output_path.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)

    for index_path in candidates:
        try:
            with open(index_path, 'r', encoding='utf-8') as f:
                index = json.load(f)
            if index.get('schemaVersion') != catalog_indexer.INDEX_SCHEMA_VERSION:
                continue
            if version and index.get('version') != version:
                continue
            return index
        except Exception:
            try:
                index_path.unlink()
            except Exception:
                pass
    return None


def _refresh_metadata(output_dir: str, quality: str = "HD", progress_callback=None):
    def report_progress(message):
        if progress_callback:
            progress_callback(message)
        print(message)

    try:
        output_path = Path(output_dir)
        output_path.mkdir(parents=True, exist_ok=True)

        report_progress(f"Fetching CDN version for {quality} quality...")
        latest_version = cdn_downloader.get_cdn_version(quality)
        if not latest_version:
            return False, "Failed to get CDN version.", None, None

        characters_json_path = output_path / "characters.json"
        stored_version = None
        if characters_json_path.exists():
            try:
                with open(characters_json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                stored_version = data.get("version")
            except (json.JSONDecodeError, KeyError, TypeError):
                stored_version = None

        existing_index = _load_valid_local_index(output_dir, quality, latest_version)
        characters_up_to_date = stored_version == latest_version and characters_json_path.exists()
        index_up_to_date = existing_index is not None

        if characters_up_to_date and index_up_to_date:
            report_progress(f"Metadata already up to date for version {latest_version}.")
            return True, "SKIPPED", latest_version, existing_index

        report_progress(f"Refreshing shared metadata for version {latest_version}...")
        with catalog_cache_lock:
            _prune_catalog_cache(latest_version)
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, quality, latest_version, catalog_cache, catalog_cache_lock, progress_callback
        )
        if error:
            return False, error, None, None

        if not characters_up_to_date:
            report_progress("Rebuilding characters.json from shared catalog...")
            success, message = character_scraper.scrape_and_save_from_catalog(output_dir, latest_version, catalog_content)
            if not success:
                return False, message, None, None

        report_progress("Rebuilding/loading asset index from shared catalog...")
        index = catalog_indexer.load_or_build_asset_index(output_dir, quality, latest_version, catalog_content)
        return True, "SUCCESS", latest_version, index
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        report_progress(f"A critical error occurred while refreshing metadata: {error_message}")
        return False, error_message, None, None


def unpack_bundle(bundle_path: str, output_dir: str, progress_callback=None):
    """
    Entry point for Kotlin to unpack a bundle.
    Returns a tuple: (success: Boolean, message: String)
    """
    try:
        success, message = unpacker_main(
            bundle_path=bundle_path,
            output_dir=output_dir,
            progress_callback=progress_callback
        )

        print(message)
        return success, message

    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        print(f"An error occurred during unpack: {error_message}")
        if progress_callback:
            progress_callback(f"An error occurred: {e}")
        return False, error_message


def download_bundle(hashed_name, quality, output_dir, cache_key, progress_callback=None):
    """
    Entry point for Kotlin to download a bundle from the CDN.
    Manages a shared in-memory cache for the catalog file to avoid redundant downloads.
    Returns a tuple: (success: Boolean, message_or_path: String)
    """
    global current_cache_key

    def report_progress(message):
        if progress_callback:
            progress_callback(message)
        print(message)

    try:
        with catalog_cache_lock:
            if current_cache_key != cache_key:
                report_progress("New batch installation detected, clearing catalog cache.")
                catalog_cache.clear()
                current_cache_key = cache_key

        report_progress(f"Fetching CDN version for {quality} quality...")
        version = cdn_downloader.get_cdn_version(quality)
        if not version:
            return False, "Failed to get CDN version."

        report_progress(f"Latest version is {version}. Checking catalog...")
        with catalog_cache_lock:
            _prune_catalog_cache(version)
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, quality, version, catalog_cache, catalog_cache_lock, progress_callback
        )
        if error:
            return False, error

        report_progress(f"Searching for bundle {hashed_name} in catalog...")
        output_file_path, error = cdn_downloader.find_and_download_bundle(
            catalog_content=catalog_content,
            version=version,
            quality=quality,
            hashed_name=hashed_name,
            output_dir=output_dir,
            progress_callback=progress_callback
        )

        if error:
            return False, error

        return True, output_file_path

    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        report_progress(f"A critical error occurred: {error_message}")
        return False, error_message


def ensure_asset_index(output_dir: str, quality: str = "HD", progress_callback=None):
    def report_progress(message):
        if progress_callback:
            progress_callback(message)
        print(message)

    try:
        output_path = Path(output_dir)
        output_path.mkdir(parents=True, exist_ok=True)
        version = cdn_downloader.get_cdn_version(quality)
        if not version:
            return False, "Failed to get CDN version.", None

        index = _load_valid_local_index(output_dir, quality, version)
        if index is None:
            return False, "Asset index missing or stale. Refresh metadata first.", None

        report_progress(f"Using local asset index for version {version}.")
        return True, version, index
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        report_progress(f"A critical error occurred while loading asset index: {error_message}")
        return False, error_message, None


def resolve_mod_files(file_names_json: str, output_dir: str, quality: str = "HD", progress_callback=None):
    try:
        file_names = json.loads(file_names_json) if isinstance(file_names_json, str) else file_names_json
        success, version_or_error, index = ensure_asset_index(output_dir, quality, progress_callback)
        if not success:
            return False, version_or_error

        result = resolver.resolve_mod_folder(file_names, index)
        return True, json.dumps(result)
    except Exception as e:
        import traceback
        return False, traceback.format_exc()


def resolve_mod_batch(mods_json: str, output_dir: str, quality: str = "HD", progress_callback=None):
    try:
        mods = json.loads(mods_json) if isinstance(mods_json, str) else mods_json
        success, version_or_error, index = ensure_asset_index(output_dir, quality, progress_callback)
        if not success:
            return False, version_or_error

        results = []
        for mod in mods or []:
            mod_id = mod.get("id")
            file_names = mod.get("fileNames") or []
            resolved = resolver.resolve_mod_folder(file_names, index)
            results.append({
                "id": mod_id,
                "result": resolved
            })
        return True, json.dumps(results)
    except Exception:
        import traceback
        return False, traceback.format_exc()


def update_character_data(output_dir: str):
    """
    Entry point for Kotlin to refresh all shared metadata.
    Returns a tuple: (status: String, message: String)
    Status can be "SUCCESS", "SKIPPED", "FAILED"
    """
    try:
        success, status, version, _index = _refresh_metadata(output_dir, "HD")
        if success:
            if status == "SKIPPED":
                return "SKIPPED", "characters.json and asset index are already up to date."
            return "SUCCESS", f"Metadata refreshed for version {version}."
        return "FAILED", status
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        print(f"An error occurred during metadata refresh process: {error_message}")
        return "FAILED", error_message


def main(original_bundle_path: str, modded_assets_folder: str, output_path: str, use_astc: bool, progress_callback=None):
    """
    Main entry point to be called from Kotlin.
    Returns a tuple: (success: Boolean, message: String)
    """
    try:
        success, message = repack_bundle(
            original_bundle_path=original_bundle_path,
            modded_assets_folder=modded_assets_folder,
            output_path=output_path,
            use_astc=use_astc,
            progress_callback=progress_callback
        )

        print(message)
        return success, message

    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        print(f"An error occurred: {error_message}")
        return False, error_message


def merge_spine_assets(mod_dir_path: str, progress_callback=None):
    """
    Entry point for Kotlin to run the spine merger script.
    Returns a tuple: (success: Boolean, message: String)
    """
    def report_progress(message):
        if progress_callback:
            progress_callback(message)
        print(message)

    try:
        message = spine_merger.run(mod_dir_path, report_progress)
        report_progress(message)
        return True, message
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        report_progress(f"An error occurred during spine merge: {error_message}")
        return False, error_message
