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
import local_bundle_indexer
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


# ---------------------------------------------------------------------------
# Character metadata (characters.json) — still uses CDN catalog
# ---------------------------------------------------------------------------

def _refresh_character_data(output_dir, quality="HD", progress_callback=None):
    """
    Download the catalog and rebuild characters.json only.
    The asset index is now handled separately by local bundle scanning.
    """
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
            return False, "Failed to get CDN version.", None

        characters_json_path = output_path / "characters.json"
        stored_version = None
        if characters_json_path.exists():
            try:
                with open(characters_json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                stored_version = data.get("version")
            except (json.JSONDecodeError, KeyError, TypeError):
                stored_version = None

        if stored_version == latest_version and characters_json_path.exists():
            report_progress(f"characters.json already up to date for version {latest_version}.")
            return True, "SKIPPED", latest_version

        report_progress(f"Refreshing character data for version {latest_version}...")
        with catalog_cache_lock:
            _prune_catalog_cache(latest_version)
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, quality, latest_version, catalog_cache, catalog_cache_lock, progress_callback
        )
        if error:
            return False, error, None

        report_progress("Rebuilding characters.json from catalog...")
        success, message = character_scraper.scrape_and_save_from_catalog(output_dir, latest_version, catalog_content)
        if not success:
            return False, message, None

        return True, "SUCCESS", latest_version
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        report_progress(f"Error refreshing character data: {error_message}")
        return False, error_message, None


def update_character_data(output_dir, quality="HD"):
    """
    Entry point for Kotlin to refresh character metadata (characters.json).
    Returns a tuple: (status: String, message: String)
    Status can be "SUCCESS", "SKIPPED", "FAILED"
    """
    try:
        success, status, version = _refresh_character_data(output_dir, quality)
        if success:
            if status == "SKIPPED":
                return "SKIPPED", "characters.json is already up to date."
            return "SUCCESS", f"Character data refreshed for version {version}."
        return "FAILED", status
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        print(f"An error occurred during character data refresh: {error_message}")
        return "FAILED", error_message


# ---------------------------------------------------------------------------
# Local bundle scanning — three-step API for Kotlin/Shizuku
# ---------------------------------------------------------------------------
#
# Usage from Kotlin:
#
#   // Step 1: List Shared/ via Shizuku, build bundle list, check which need scanning
#   val bundleListJson = buildBundleListJson()  // [{"name":"...", "hash":"..."}, ...]
#   val needsScanJson = mainScript.callAttr("check_scan_needed", outputDir, bundleListJson).toString()
#   val needsScan = JSONArray(needsScanJson)
#
#   // Step 2: For each bundle that needs scanning
#   for (bundleName in needsScan) {
#       val hash = bundleHashes[bundleName]
#       val tempPath = copyBundleViaShizuku(bundleName, hash)  // Copy __data to temp
#       mainScript.callAttr("scan_single_bundle", bundleName, hash, tempPath, callback)
#       File(tempPath).delete()  // Clean up temp file
#   }
#
#   // Step 3: Finalize and save index
#   mainScript.callAttr("finalize_scan", outputDir, callback)


def check_scan_needed(output_dir, bundle_list_json, progress_callback=None):
    """
    Step 1: Check which bundles need scanning.

    Kotlin should call this with the directory listing from Shared/,
    obtained via Shizuku.

    Args:
        output_dir: App-writable directory for the index cache
        bundle_list_json: JSON string of [{"name": "bundleName", "hash": "hashDir"}, ...]
        progress_callback: Optional progress reporting function

    Returns:
        JSON string of bundle names that need scanning: '["name1", "name2", ...]'
    """
    def report(msg):
        if progress_callback:
            progress_callback(msg)
        print(msg)

    try:
        result = local_bundle_indexer.check_scan_needed(output_dir, bundle_list_json)
        needs_scan = json.loads(result)
        report(f"Scan check complete: {len(needs_scan)} bundles need scanning.")
        return result
    except Exception as e:
        import traceback
        error_msg = traceback.format_exc()
        report(f"Error checking scan: {error_msg}")
        return json.dumps([])


def scan_single_bundle(bundle_name, bundle_hash, temp_data_path, progress_callback=None):
    """
    Step 2: Scan one bundle from a temporary file.

    Kotlin should:
      1. Copy __data from game dir to a temp path via Shizuku
      2. Call this function
      3. Delete the temp file

    Args:
        bundle_name: Bundle identifier (folder name under Shared/)
        bundle_hash: Hash directory name
        temp_data_path: Path to __data in app's cache dir
        progress_callback: Optional progress reporting function

    Returns:
        Tuple (success: Boolean, asset_count: int, message: String)
    """
    return local_bundle_indexer.scan_single_bundle(
        bundle_name, bundle_hash, temp_data_path, progress_callback
    )


def finalize_scan(output_dir, progress_callback=None):
    """
    Step 3: Save the final index after all bundles have been scanned.

    Args:
        output_dir: App-writable directory for the index cache
        progress_callback: Optional progress reporting function

    Returns:
        Tuple (success: Boolean, message: String)
    """
    return local_bundle_indexer.finalize_scan(output_dir, progress_callback)


# ---------------------------------------------------------------------------
# Mod resolution — uses local bundle index
# ---------------------------------------------------------------------------

def ensure_asset_index(output_dir, quality="HD", progress_callback=None):
    """
    Load the local bundle index from disk.

    Returns a tuple: (success: Boolean, message_or_error: String, index: dict or None)
    """
    def report_progress(message):
        if progress_callback:
            progress_callback(message)
        print(message)

    try:
        index = local_bundle_indexer.load_local_index(output_dir)
        if index is None:
            return False, "Local bundle index not found. Please scan local bundles first.", None

        asset_count = index.get('assetCount', 0)
        bundle_count = index.get('bundleCount', 0)
        report_progress(f"Local bundle index loaded: {bundle_count} bundles, {asset_count} assets.")
        return True, "Local index loaded.", index
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        report_progress(f"Error loading local index: {error_message}")
        return False, error_message, None


def resolve_mod_files(file_names_json, output_dir, quality="HD", progress_callback=None):
    """
    Resolve mod files against the local bundle index.

    Args:
        file_names_json: JSON string or list of mod file names
        output_dir: Path where the local index cache is stored
        quality: Ignored (kept for backward compatibility)
        progress_callback: Optional progress reporting function

    Returns a tuple: (success: Boolean, result_json_or_error: String)
    """
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


def resolve_mod_batch(mods_json, output_dir, quality="HD", progress_callback=None):
    """
    Resolve a batch of mods against the local bundle index.

    Args:
        mods_json: JSON string or list of mod objects with 'id' and 'fileNames'
        output_dir: Path where the local index cache is stored
        quality: Ignored (kept for backward compatibility)
        progress_callback: Optional progress reporting function

    Returns a tuple: (success: Boolean, results_json_or_error: String)
    """
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


# ---------------------------------------------------------------------------
# CDN bundle download — kept for backward compatibility
# ---------------------------------------------------------------------------

def download_bundle(hashed_name, quality, output_dir, cache_key, progress_callback=None):
    """
    Entry point for Kotlin to download a bundle from the CDN.
    Manages a shared in-memory cache for the catalog file to avoid redundant downloads.
    Returns a tuple: (success: Boolean, message_or_path: String)
    """
    global current_cache_key

    # FHD 選項在下載時仍使用 HD 資源（CDN 無獨立 FHD 路徑）
    download_quality = "HD" if quality == "FHD" else quality

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

        report_progress(f"Fetching CDN version for {download_quality} quality...")
        version = cdn_downloader.get_cdn_version(download_quality)
        if not version:
            return False, "Failed to get CDN version."

        report_progress(f"Latest version is {version}. Checking catalog...")
        with catalog_cache_lock:
            _prune_catalog_cache(version)
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, download_quality, version, catalog_cache, catalog_cache_lock, progress_callback
        )
        if error:
            return False, error

        report_progress(f"Searching for bundle {hashed_name} in catalog...")
        output_file_path, error = cdn_downloader.find_and_download_bundle(
            catalog_content=catalog_content,
            version=version,
            quality=download_quality,
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


# ---------------------------------------------------------------------------
# Unpacking, repacking, spine merge — unchanged
# ---------------------------------------------------------------------------

def unpack_bundle(bundle_path, output_dir, progress_callback=None):
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


def main(original_bundle_path, modded_assets_folder, output_path, use_astc, progress_callback=None):
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


def merge_spine_assets(mod_dir_path, progress_callback=None):
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
