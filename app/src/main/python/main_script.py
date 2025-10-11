import sys
import os

# Add the vendor directory to the sys.path to allow importing bundled packages
sys.path.append(os.path.join(os.path.dirname(__file__), "vendor"))

from repacker.repacker import repack_bundle
import character_scraper
import cdn_downloader
from unpacker import unpack_bundle as unpacker_main

def unpack_bundle(bundle_path: str, output_dir: str, progress_callback=None):
    """
    Entry point for Kotlin to unpack a bundle.
    Returns a tuple: (success: Boolean, message: String)
    """
    try:
        # The progress callback will handle printing.
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

def download_bundle(hashed_name, quality, output_dir, progress_callback=None):
    """
    Entry point for Kotlin to download a bundle from the CDN.
    Returns a tuple: (success: Boolean, message_or_path: String)
    """
    def report_progress(message):
        if progress_callback:
            progress_callback(message)
        print(message)

    try:
        report_progress(f"Fetching CDN version for {quality} quality...")
        version = cdn_downloader.get_cdn_version(quality)
        if not version:
            return False, "Failed to get CDN version."
        
        report_progress(f"Latest version is {version}. Checking catalog...")
        catalog_path, error = cdn_downloader.download_catalog(output_dir, quality, version, progress_callback)
        if error:
            return False, error

        report_progress(f"Searching for bundle {hashed_name} in catalog...")
        output_file_path, error = cdn_downloader.find_and_download_bundle(
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


def main(original_bundle_path: str, modded_assets_folder: str, output_path: str, progress_callback=None):
    """
    Main entry point to be called from Kotlin.
    Returns a tuple: (success: Boolean, message: String)
    """
    try:
        # The progress callback will handle printing, so we can remove the print statements here.
        success = repack_bundle(
            original_bundle_path=original_bundle_path,
            modded_assets_folder=modded_assets_folder,
            output_path=output_path,
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
