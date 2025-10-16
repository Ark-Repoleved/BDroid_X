import sys
import os
import threading

# Add the vendor directory to the sys.path to allow importing bundled packages
sys.path.append(os.path.join(os.path.dirname(__file__), "vendor"))

from repacker.repacker import repack_bundle
import character_scraper
import cdn_downloader
from unpacker import unpack_bundle as unpacker_main

# --- Global Cache for CDN Catalog ---
# In-memory cache for the catalog JSON content.
# The key is the version, the value is the parsed JSON content.
catalog_cache = {}
# A lock to ensure thread-safe access to the cache for cache *clearing*.
# We no longer use it for downloads, but clearing the cache should be atomic.
catalog_clear_lock = threading.Lock()
# A variable to track the current cache key (e.g., a timestamp for the batch install)
# to ensure that different installation processes use different caches.
current_cache_key = None

def unpack_bundle(bundle_path: str, output_dir: str, progress_callback=None):
    """
    Entry point for Kotlin to unpack a bundle.
    Ensures the output directory is cleaned up if it exists, especially on failure.
    """
    import shutil
    try:
        # Ensure the output directory is clean before starting.
        if os.path.exists(output_dir):
            shutil.rmtree(output_dir)
        os.makedirs(output_dir, exist_ok=True)

        success, message = unpacker_main(
            bundle_path=bundle_path,
            output_dir=output_dir,
            progress_callback=progress_callback
        )
        
        print(message)
        # If successful, the output_dir is the result and should not be cleaned.
        return success, message
            
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        print(f"An error occurred during unpack: {error_message}")
        if progress_callback:
            progress_callback(f"An error occurred: {e}")
        
        # --- Cleanup on Failure ---
        # If an error occurs, we treat the entire output directory as temporary and remove it.
        try:
            if os.path.exists(output_dir):
                shutil.rmtree(output_dir)
                print(f"Cleaned up failed unpack directory: {output_dir}")
        except Exception as cleanup_e:
            print(f"Error during unpack cleanup: {cleanup_e}")
            
        return False, error_message

def download_bundle(hashed_name, quality, output_dir, cache_key, progress_callback=None):
    """
    Entry point for Kotlin to download a bundle from the CDN.
    Includes a finally block to ensure temporary files are cleaned up immediately
    after the operation, regardless of success or failure.
    """
    global current_cache_key
    def report_progress(message):
        if progress_callback:
            progress_callback(message)
        print(message)

    # This function now manages its own temporary files.
    # The downloaded bundle (`__data_{hashed_name}`) is considered an artifact,
    # and its lifecycle should be managed by the calling process (e.g., the unpacking function).
    temp_catalog_path = None

    try:
        # --- Cache Management ---
        with catalog_clear_lock:
            if current_cache_key != cache_key:
                report_progress("New batch installation detected, clearing catalog cache.")
                catalog_cache.clear()
                current_cache_key = cache_key
        
        report_progress(f"Fetching CDN version for {quality} quality...")
        version = cdn_downloader.get_cdn_version(quality)
        if not version:
            return False, "Failed to get CDN version."
        
        temp_catalog_path = os.path.join(output_dir, f"catalog_{version}.json")

        report_progress(f"Latest version is {version}. Checking catalog...")
        catalog_content, error = cdn_downloader.download_catalog(
            output_dir, quality, version, catalog_cache, progress_callback
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
    finally:
        # --- Immediate Cleanup of Catalog ---
        try:
            if temp_catalog_path and os.path.exists(temp_catalog_path):
                report_progress(f"Cleaning up temporary catalog: {temp_catalog_path}")
                os.remove(temp_catalog_path)
        except Exception as e:
            report_progress(f"Error during catalog cleanup: {e}")

def update_character_data(output_dir: str):
    """
    Entry point for Kotlin to run the character data scraper.
    Returns a tuple: (success: Boolean, message: String)
    """
    try:
        success = character_scraper.scrape_and_save(output_dir)
        if success:
            message = "Scraper completed successfully."
            print(message)
            return True, message
        else:
            message = "Scraper failed without an exception."
            print(message)
            return False, message
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        print(f"An error occurred during scraping: {error_message}")
        return False, error_message


def main(original_bundle_path: str, modded_assets_folder: str, output_path: str, use_astc: bool, progress_callback=None):
    """
    Main entry point to be called from Kotlin.
    Ensures that the temporary modded assets folder is cleaned up after the operation.
    """
    import shutil
    try:
        success = repack_bundle(
            original_bundle_path=original_bundle_path,
            modded_assets_folder=modded_assets_folder,
            output_path=output_path,
            use_astc=use_astc,
            progress_callback=progress_callback
        )
        
        if success:
            message = "Repack process completed successfully."
            print(message)
            return True, message
        else:
            message = "Repack process failed without an exception."
            print(message)
            return False, message
            
    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        print(f"An error occurred: {error_message}")
        return False, error_message
    finally:
        # --- Immediate Cleanup of Modded Assets Folder ---
        # The modded assets folder is always considered temporary.
        try:
            if os.path.exists(modded_assets_folder):
                shutil.rmtree(modded_assets_folder)
                print(f"Cleaned up temporary assets folder: {modded_assets_folder}")
        except Exception as e:
            print(f"Error during repack cleanup: {e}")
