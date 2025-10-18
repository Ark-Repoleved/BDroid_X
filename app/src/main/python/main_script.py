import sys
import os
import threading
import traceback

# Add the vendor directory to the sys.path to allow importing bundled packages
sys.path.append(os.path.join(os.path.dirname(__file__), "vendor"))

from repacker.repacker import repack_bundle
import character_scraper
import cdn_downloader

# --- MODIFICATION START ---
# Import both unpack methods from the unpacker module and give them aliases
from unpacker import unpack_bundle as unpacker_main
from unpacker import unpack_bundle_by_fd as unpacker_main_by_fd
# --- MODIFICATION END ---


# --- Global Cache for CDN Catalog ---
catalog_cache = {}
catalog_cache_lock = threading.Lock()
current_cache_key = None

# --- NEW FUNCTION START ---
def unpack_bundle_by_fd(bundle_fd: int, output_dir: str, progress_callback=None):
    """
    Entry point for Kotlin to unpack a bundle using a file descriptor.
    Returns a tuple: (success: Boolean, message: String)
    """
    try:
        success, message = unpacker_main_by_fd(
            bundle_fd=bundle_fd,
            output_dir=output_dir,
            progress_callback=progress_callback
        )
        print(message)
        return success, message
    except Exception as e:
        error_message = traceback.format_exc()
        print(f"An error occurred during unpack by fd: {error_message}")
        if progress_callback:
            progress_callback(f"An error occurred: {e}")
        return False, error_message
# --- NEW FUNCTION END ---

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
        error_message = traceback.format_exc()
        report_progress(f"A critical error occurred: {error_message}")
        return False, error_message

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
        error_message = traceback.format_exc()
        print(f"An error occurred during scraping: {error_message}")
        return False, error_message


def main(original_bundle_path: str, modded_assets_folder: str, output_path: str, use_astc: bool, progress_callback=None):
    """
    Main entry point to be called from Kotlin.
    Returns a tuple: (success: Boolean, message: String)
    """
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
        error_message = traceback.format_exc()
        print(f"An error occurred: {error_message}")
        return False, error_message