import os
import sys

# Add the project's vendored UnityPy to the path
vendor_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor")
sys.path.insert(0, vendor_path)

try:
    import UnityPy
    from UnityPy.helpers import TypeTreeHelper
except ImportError:
    print(f"Error: Could not import UnityPy. Make sure it exists in '{vendor_path}'")
    sys.exit(1)

def unpack_bundle(bundle_path, output_dir, progress_callback=print):
    """
    Unpacks a Unity bundle file to a specified directory.
    """
    progress_callback(f"Starting to unpack '{os.path.basename(bundle_path)}'...")
    
    if not os.path.exists(bundle_path):
        progress_callback(f"Error: Bundle file not found at '{bundle_path}'")
        return (False, f"Bundle file not found at '{bundle_path}'")

    os.makedirs(output_dir, exist_ok=True)
    progress_callback(f"Output directory '{output_dir}' is ready.")
    
    TypeTreeHelper.read_typetree_boost = False
    UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'
    
    try:
        env = UnityPy.load(bundle_path)
        total_objects = len(env.objects)
        progress_callback(f"Successfully loaded bundle. Found {total_objects} assets.")

        for i, obj in enumerate(env.objects):
            try:
                data = obj.read()
                
                if not hasattr(data, 'm_Name') or not data.m_Name:
                    continue

                dest_name = data.m_Name.replace('/', '_')
                dest_path = os.path.join(output_dir, dest_name)
                
                current_progress = f"Processing asset {i+1}/{total_objects}: {dest_name}"
                progress_callback(current_progress)

                if obj.type.name == "Texture2D":
                    if not dest_path.lower().endswith((".png", ".jpg", ".jpeg")):
                        dest_path += ".png"
                    data.image.save(dest_path)

                elif obj.type.name == "TextAsset":
                    with open(dest_path, "wb") as f:
                        content = data.m_Script
                        if isinstance(content, str):
                            content = content.encode('utf-8', 'surrogateescape')
                        f.write(content)

                elif obj.type.name == "MonoBehaviour":
                     if ".skel" in dest_name:
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
                error_message = f"FAILED to export asset '{asset_name}': {e}"
                progress_callback(error_message)
                print(traceback.format_exc())
        
        progress_callback("Unpacking complete.")
        return (True, "Unpacking complete.")
        
    except Exception as e:
        import traceback
        error_message = f"Failed to load bundle: {e}"
        progress_callback(error_message)
        print(traceback.format_exc())
        return (False, error_message)
