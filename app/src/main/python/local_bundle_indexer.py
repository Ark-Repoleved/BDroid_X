# -*- coding: utf-8 -*-
"""
Scans locally cached game bundles to build an asset-name-to-bundle mapping.

Provides a three-step API designed for Kotlin/Shizuku integration:

  Step 1: check_scan_needed()  — Compare bundle list with cache, return which need scanning
  Step 2: scan_single_bundle() — Scan one bundle from a temp file (called per bundle)
  Step 3: finalize_scan()      — Merge cached + new results, save final index

This architecture lets the Kotlin layer handle Shizuku-mediated file I/O
(copying __data files from the game's private directory to a temp path)
while Python handles only the UnityPy parsing.
"""
import json
import os
import time
import glob

# Lazy-loaded UnityPy reference
_UnityPy = None

INDEX_SCHEMA_VERSION = 2

# Only scan object types that carry meaningful m_Name for modding.
# Skipping Transform, GameObject, Material, Shader, Mesh, etc. avoids
# expensive obj.read() calls and dramatically speeds up scanning.
SCAN_TYPES = frozenset({"Texture2D", "TextAsset", "Sprite"})

# Extension mapping to match unpacker/repacker conventions.
# Texture2D m_Name has no extension in Unity — we add ".png" to match
# what the unpacker exports and what users expect.
_EXTENSION_MAP = {"Texture2D": ".png", "Sprite": ".png"}

# Module-level state for an ongoing scan session.
# Populated by check_scan_needed(), updated by scan_single_bundle(),
# consumed and cleared by finalize_scan().
_scan_state = None


def _ensure_unitypy():
    """Lazy-load and configure UnityPy."""
    global _UnityPy
    if _UnityPy is not None:
        return _UnityPy

    from UnityPy.helpers import TypeTreeHelper
    TypeTreeHelper.read_typetree_boost = False
    import UnityPy
    UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'
    _UnityPy = UnityPy
    return _UnityPy


def _get_asset_extension(type_name):
    """Return the file extension for an object type, or empty string."""
    return _EXTENSION_MAP.get(type_name, "")


def _read_name_fast(obj):
    """Read m_Name directly from raw object data, skipping full deserialization.

    For Texture2D, TextAsset, and Sprite, m_Name is the first serialized field.
    Unity string format: [int32 length][UTF-8 bytes][padding to 4-byte align]

    By reading only the name, we skip parsing texture image data, script content,
    sprite meshes, etc. — which is the vast majority of the data.
    """
    try:
        obj.reset()  # seek reader to object's byte_start
        return obj.reader.read_aligned_string()
    except Exception:
        # Fallback: full deserialization (slow but guaranteed correct)
        try:
            data = obj.read()
            return getattr(data, "m_Name", None)
        except Exception:
            return None


def _scan_bundle_file(file_path):
    """
    Scan a single bundle file and return a sorted list of unique asset names.

    Only reads objects of types in SCAN_TYPES to minimize parsing overhead.
    Uses fast name extraction (raw binary read) instead of full object
    deserialization to avoid loading texture data, scripts, etc.
    """
    unitypy = _ensure_unitypy()
    env = unitypy.load(file_path)
    names = set()

    for obj in env.objects:
        if obj.type.name not in SCAN_TYPES:
            continue

        raw_name = _read_name_fast(obj)
        if not raw_name:
            continue

        name = raw_name.strip().lower()
        if not name:
            continue

        ext = _get_asset_extension(obj.type.name)
        if ext and not name.endswith(ext):
            name += ext

        names.add(name)

    return sorted(names)


def _load_existing_cache(cache_path):
    """Load existing index cache from disk. Returns dict or None."""
    try:
        if os.path.isfile(cache_path):
            with open(cache_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if data.get("schemaVersion") == INDEX_SCHEMA_VERSION:
                return data
    except Exception:
        pass
    return None


# ---------------------------------------------------------------------------
# Three-step scanning API
# ---------------------------------------------------------------------------

def check_scan_needed(output_dir, bundle_list_json):
    """
    Step 1: Compare the current bundle list against the cached index to
    determine which bundles need scanning.

    Kotlin should call this first with the directory listing obtained via
    Shizuku from the game's Shared/ directory.

    Args:
        output_dir: Path where the index cache JSON lives (app-writable dir)
        bundle_list_json: JSON string or list of objects:
            [{"name": "bundleName", "hash": "hashDirName"}, ...]

    Returns:
        JSON string of bundle names that need scanning: ["name1", "name2", ...]
        Bundles whose hash matches the cache are skipped.
    """
    global _scan_state

    bundle_list = json.loads(bundle_list_json) if isinstance(bundle_list_json, str) else bundle_list_json

    cache_path = os.path.join(output_dir, "local_bundle_index.json")
    existing_cache = _load_existing_cache(cache_path)
    cached_bundles = existing_cache.get("scannedBundles", {}) if existing_cache else {}

    needs_scan = []
    still_valid = {}

    for item in bundle_list:
        name = item["name"]
        hash_ = item["hash"]
        cached = cached_bundles.get(name)
        if cached and cached.get("hash") == hash_:
            # Hash unchanged — reuse cached scan result
            still_valid[name] = cached
        else:
            # New or updated — needs scanning
            needs_scan.append(name)

    # Initialize/reset scan state
    _scan_state = {
        "output_dir": output_dir,
        "all_bundle_hashes": {item["name"]: item["hash"] for item in bundle_list},
        "cached": still_valid,
        "scanned": {},
    }

    return json.dumps(needs_scan)


def scan_single_bundle(bundle_name, bundle_hash, temp_data_path, progress_callback=None):
    """
    Step 2: Scan a single bundle from a temporary file path.

    Kotlin should:
      1. Copy the __data file from the game directory to a temp path via Shizuku
      2. Call this function with the temp path
      3. Delete the temp file after this function returns

    This is called once per bundle that check_scan_needed() flagged.

    Args:
        bundle_name:    Bundle identifier (folder name under Shared/)
        bundle_hash:    Hash directory name (for cache key)
        temp_data_path: Path to the __data file in app's accessible cache dir
        progress_callback: Optional function(str)

    Returns:
        Tuple (success: bool, asset_count: int, message: str)
    """
    global _scan_state

    if _scan_state is None:
        return False, 0, "No scan in progress. Call check_scan_needed first."

    try:
        assets = _scan_bundle_file(temp_data_path)
        _scan_state["scanned"][bundle_name] = {
            "hash": bundle_hash,
            "assets": assets,
        }
        if progress_callback:
            progress_callback(f"Scanned {bundle_name}: {len(assets)} assets")
        return True, len(assets), f"OK: {len(assets)} assets"

    except Exception as e:
        _scan_state["scanned"][bundle_name] = {
            "hash": bundle_hash,
            "assets": [],
            "error": str(e),
        }
        if progress_callback:
            progress_callback(f"Failed {bundle_name}: {e}")
        return False, 0, str(e)


def _get_bundle_to_download_name_map(output_dir):
    """
    Attempts to read the latest catalog in output_dir to extract the
    mapping of actual bundleName to Addressables Key (downloadName).
    """
    try:
        from catalog_parser import read_int32_from_byte_array, read_object_from_byte_array
    except ImportError:
        return {}
    import base64

    catalogs = glob.glob(os.path.join(output_dir, "catalog_*.json"))
    if not catalogs:
        return {}
    
    # Use the most recently modified one if there are multiple
    catalog_path = sorted(catalogs, key=os.path.getmtime, reverse=True)[0]
    
    try:
        with open(catalog_path, 'r', encoding='utf-8') as f:
            catalog = json.load(f)
            
        provider_ids = catalog.get("m_ProviderIds", [])
        bundle_provider = "UnityEngine.ResourceManagement.ResourceProviders.AssetBundleProvider"
        if bundle_provider not in provider_ids:
            return {}
        bundle_provider_index = provider_ids.index(bundle_provider)

        bucket_array = base64.b64decode(catalog["m_BucketDataString"])
        key_array = base64.b64decode(catalog["m_KeyDataString"])
        extra_data = base64.b64decode(catalog["m_ExtraDataString"])
        entry_data = base64.b64decode(catalog["m_EntryDataString"])

        num_buckets = read_int32_from_byte_array(bucket_array, 0)
        data_offsets = []
        index = 4
        for _ in range(num_buckets):
            data_offsets.append(read_int32_from_byte_array(bucket_array, index))
            index += 4
            num_entries = read_int32_from_byte_array(bucket_array, index)
            index += 4
            index += 4 * num_entries

        keys = [read_object_from_byte_array(key_array, offset) for offset in data_offsets]

        mapping = {}
        number_of_entries = read_int32_from_byte_array(entry_data, 0)
        index = 4
        for _ in range(number_of_entries):
            # internal_id
            index += 4
            # provider_index
            provider_index = read_int32_from_byte_array(entry_data, index)
            index += 4
            # dependency_key_index
            index += 4
            # dependency_hash
            index += 4
            # data_index (extra_data offset)
            data_index = read_int32_from_byte_array(entry_data, index)
            index += 4
            # primary_key_index
            primary_key_index = read_int32_from_byte_array(entry_data, index)
            index += 4
            # resource_type
            index += 4

            if provider_index == bundle_provider_index and data_index >= 0:
                bundle_info = read_object_from_byte_array(extra_data, data_index)
                if bundle_info and bundle_info.get("m_BundleName"):
                    bundle_name = str(bundle_info["m_BundleName"])
                    download_name = str(keys[primary_key_index]) if primary_key_index < len(keys) else ""
                    mapping[bundle_name] = download_name

        return mapping
    except Exception as e:
        print(f"Error extracting downloadNames: {e}")
        return {}


def finalize_scan(output_dir, progress_callback=None):
    """
    Step 3: Merge cached + newly scanned results and save the final index.

    Should be called after all scan_single_bundle() calls are complete
    (or after deciding to stop scanning early — partial results are fine).

    Args:
        output_dir: Path to save the index cache JSON
        progress_callback: Optional function(str)

    Returns:
        Tuple (success: bool, message: str)
    """
    global _scan_state

    def report(msg):
        if progress_callback:
            progress_callback(msg)
        print(msg)

    if _scan_state is None:
        return False, "No scan in progress. Call check_scan_needed first."

    try:
        # Merge cached and newly scanned bundles
        all_bundles = {}
        all_bundles.update(_scan_state["cached"])
        all_bundles.update(_scan_state["scanned"])

        cached_count = len(_scan_state["cached"])
        scanned_count = len(_scan_state["scanned"])
        failed_count = sum(
            1 for info in _scan_state["scanned"].values() if info.get("error")
        )

        # Build the assetToBundles lookup table
        asset_to_bundles = {}
        for bundle_name, info in all_bundles.items():
            for asset_name in info.get("assets", []):
                if asset_name not in asset_to_bundles:
                    asset_to_bundles[asset_name] = []
                if bundle_name not in asset_to_bundles[asset_name]:
                    asset_to_bundles[asset_name].append(bundle_name)

        # Enhance with downloadNames using the catalog mapping
        download_names = _get_bundle_to_download_name_map(output_dir)
        for b_name, b_info in all_bundles.items():
            b_info["downloadName"] = download_names.get(b_name, "")

        # Save index to disk
        os.makedirs(output_dir, exist_ok=True)
        index = {
            "schemaVersion": INDEX_SCHEMA_VERSION,
            "scannedAt": int(time.time()),
            "bundleCount": len(all_bundles),
            "assetCount": len(asset_to_bundles),
            "assetToBundles": asset_to_bundles,
            "scannedBundles": all_bundles,
        }

        cache_path = os.path.join(output_dir, "local_bundle_index.json")
        with open(cache_path, "w", encoding="utf-8") as f:
            json.dump(index, f, ensure_ascii=False, separators=(",", ":"))

        msg = (
            f"Index saved: {len(all_bundles)} bundles, {len(asset_to_bundles)} assets "
            f"(cached: {cached_count}, scanned: {scanned_count}, failed: {failed_count})"
        )
        report(msg)
        return True, msg

    except Exception as e:
        import traceback
        error_msg = traceback.format_exc()
        report(f"Error finalizing scan: {error_msg}")
        return False, error_msg

    finally:
        # Always clear state, even on error
        _scan_state = None


# ---------------------------------------------------------------------------
# Index loading (used by resolver)
# ---------------------------------------------------------------------------

def load_local_index(output_dir):
    """
    Load the local bundle index from disk cache.

    Returns:
        The index dict, or None if no valid cache exists.
    """
    cache_path = os.path.join(output_dir, "local_bundle_index.json")
    return _load_existing_cache(cache_path)
