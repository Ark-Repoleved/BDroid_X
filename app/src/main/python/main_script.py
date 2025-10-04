import sys
import os

# Add the vendor directory to the sys.path to allow importing bundled packages
sys.path.append(os.path.join(os.path.dirname(__file__), "vendor"))

from repacker.repacker import repack_bundle

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
