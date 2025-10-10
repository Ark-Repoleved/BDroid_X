import os
import sys

# Add the project's vendored UnityPy to the path
vendor_path = "/workspaces/Ark/bd2-android-mod-manager/app/src/main/python/vendor"
sys.path.insert(0, vendor_path)

try:
    import UnityPy
    from UnityPy.helpers import TypeTreeHelper
except ImportError:
    print(f"Error: Could not import UnityPy. Make sure it exists in '{vendor_path}'")
    sys.exit(1)

# --- Script configuration ---
BUNDLE_PATH = '/workspaces/Ark/__data'
OUTPUT_DIR = '/workspaces/Ark/output'
# --- End of configuration ---

def unpack_bundle(bundle_path, output_dir, progress_callback=print):
    """
    Unpacks a Unity bundle file to a specified directory.
    """
    print(f"Starting to unpack '{bundle_path}' into '{output_dir}'...")
    
    if not os.path.exists(bundle_path):
        print(f"Error: Bundle file not found at '{bundle_path}'")
        return

    os.makedirs(output_dir, exist_ok=True)
    print(f"Output directory '{output_dir}' is ready.")
    
    TypeTreeHelper.read_typetree_boost = False
    UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'
    env = UnityPy.load(bundle_path)
    
    print(f"Successfully loaded bundle. Found {len(env.objects)} assets.")

    for obj in env.objects:
        try:
            data = obj.read()
            
            if not hasattr(data, 'm_Name') or not data.m_Name:
                continue

            dest_name = data.m_Name.replace('/', '_')
            dest_path = os.path.join(output_dir, dest_name)

            if obj.type.name == "Texture2D":
                if not dest_path.lower().endswith((".png", ".jpg", ".jpeg")):
                    dest_path += ".png"
                print(f"  Exporting Texture2D: {dest_name} -> {dest_path}")
                data.image.save(dest_path)

            elif obj.type.name == "TextAsset":
                print(f"  Exporting TextAsset: {dest_name}")
                with open(dest_path, "wb") as f:
                    content = data.m_Script
                    if isinstance(content, str):
                        content = content.encode('utf-8', 'surrogateescape')
                    f.write(content)

            elif obj.type.name == "MonoBehaviour":
                 if ".skel" in dest_name:
                     print(f"  Exporting MonoBehaviour (skel): {dest_name}")
                     with open(dest_path, "wb") as f:
                        content = data.m_Script
                        if isinstance(content, str):
                            content = content.encode('utf-8', 'surrogateescape')
                        f.write(content)

        except Exception as e:
            import traceback
            asset_name = "Unknown"
            if 'data' in locals() and hasattr(data, 'm_Name'):
                asset_name = data.m_Name
            print(f"  -> FAILED to export asset '{asset_name}': {traceback.format_exc()}")

    print("Unpacking complete.")

if __name__ == "__main__":
    unpack_bundle(BUNDLE_PATH, OUTPUT_DIR)
