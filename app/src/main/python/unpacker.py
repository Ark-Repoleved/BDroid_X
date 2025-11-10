import os
import sys
import ctypes
from ctypes import *
import gc
from PIL import Image

# Add the project's vendored UnityPy to the path
vendor_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor")
sys.path.insert(0, vendor_path)

try:
    import UnityPy
    from UnityPy.helpers import TypeTreeHelper
except ImportError:
    print(f"Error: Could not import UnityPy. Make sure it exists in '{vendor_path}'")
    sys.exit(1)

# --- ctypes definitions for libastcenc ---
ASTCENC_SUCCESS = 0
ASTCENC_PRF_LDR_SRGB = 0
ASTCENC_PRE_MEDIUM = 60.0
ASTCENC_TYPE_U8 = 0
ASTCENC_FLG_USE_DECODE_UNORM8 = 1 << 1

class astcenc_swizzle(Structure):
    _fields_ = [("r", c_uint), ("g", c_uint), ("b", c_uint), ("a", c_uint)]

class astcenc_config(Structure):
    _fields_ = [
        ("profile", c_uint),
        ("flags", c_uint),
        ("block_x", c_uint),
        ("block_y", c_uint),
        ("block_z", c_uint),
        ("cw_r_weight", c_float),
        ("cw_g_weight", c_float),
        ("cw_b_weight", c_float),
        ("cw_a_weight", c_float),
        ("a_scale_radius", c_uint),
        ("rgbm_m_scale", c_float),
        ("tune_partition_count_limit", c_uint),
        ("tune_2partition_index_limit", c_uint),
        ("tune_3partition_index_limit", c_uint),
        ("tune_4partition_index_limit", c_uint),
        ("tune_block_mode_limit", c_uint),
        ("tune_refinement_limit", c_uint),
        ("tune_candidate_limit", c_uint),
        ("tune_2partitioning_candidate_limit", c_uint),
        ("tune_3partitioning_candidate_limit", c_uint),
        ("tune_4partitioning_candidate_limit", c_uint),
        ("tune_db_limit", c_float),
        ("tune_mse_overshoot", c_float),
        ("tune_2partition_early_out_limit_factor", c_float),
        ("tune_3partition_early_out_limit_factor", c_float),
        ("tune_2plane_early_out_limit_correlation", c_float),
        ("tune_search_mode0_enable", c_float),
        ("progress_callback", c_void_p),
    ]

class astcenc_image(Structure):
    _fields_ = [
        ("dim_x", c_uint),
        ("dim_y", c_uint),
        ("dim_z", c_uint),
        ("data_type", c_uint),
        ("data", POINTER(c_void_p)),
    ]

try:
    astcenc = ctypes.cdll.LoadLibrary("libastcenc.so")

    astcenc.astcenc_config_init.argtypes = [c_uint, c_uint, c_uint, c_uint, c_float, c_uint, POINTER(astcenc_config)]
    astcenc.astcenc_config_init.restype = c_int
    astcenc.astcenc_context_alloc.argtypes = [POINTER(astcenc_config), c_uint, POINTER(c_void_p)]
    astcenc.astcenc_context_alloc.restype = c_int
    astcenc.astcenc_decompress_image.argtypes = [c_void_p, POINTER(c_ubyte), c_size_t, POINTER(astcenc_image), POINTER(astcenc_swizzle), c_uint]
    astcenc.astcenc_decompress_image.restype = c_int
    astcenc.astcenc_context_free.argtypes = [c_void_p]
    astcenc.astcenc_context_free.restype = None
    astcenc.astcenc_get_error_string.argtypes = [c_int]
    astcenc.astcenc_get_error_string.restype = c_char_p
except OSError as e:
    print(f"WARNING: Could not load libastcenc.so. ASTC decompression will not work. Error: {e}")
    astcenc = None

def get_error_string(status):
    if not astcenc: return "astcenc library not loaded"
    return astcenc.astcenc_get_error_string(status).decode('utf-8')

def decompress_astc_ctypes(image_data, width, height, block_x, block_y):
    if not astcenc:
        return None, "libastcenc.so not loaded."

    config = astcenc_config()
    quality = ASTCENC_PRE_MEDIUM
    profile = ASTCENC_PRF_LDR_SRGB
    flags = ASTCENC_FLG_USE_DECODE_UNORM8
    status = astcenc.astcenc_config_init(profile, block_x, block_y, 1, quality, flags, byref(config))
    if status != ASTCENC_SUCCESS:
        return None, f"astcenc_config_init failed: {get_error_string(status)}"

    context = c_void_p()
    thread_count = os.cpu_count() or 1
    status = astcenc.astcenc_context_alloc(byref(config), thread_count, byref(context))
    if status != ASTCENC_SUCCESS:
        # CORRECTED: No need to free config. Just return the error.
        return None, f"astcenc_context_alloc failed: {get_error_string(status)}"

    decompressed_buffer = None
    try:
        decompressed_size = width * height * 4
        decompressed_buffer = (c_ubyte * decompressed_size)()
        
        image_out_p = (c_void_p * 1)()
        image_out_p[0] = ctypes.cast(decompressed_buffer, c_void_p)

        image_out = astcenc_image()
        image_out.dim_x = width
        image_out.dim_y = height
        image_out.dim_z = 1
        image_out.data_type = ASTCENC_TYPE_U8
        image_out.data = image_out_p

        swizzle = astcenc_swizzle(r=0, g=1, b=2, a=3)
        comp_buf = (c_ubyte * len(image_data)).from_buffer_copy(image_data)

        status = astcenc.astcenc_decompress_image(context, comp_buf, len(image_data), byref(image_out), byref(swizzle), 0)
        
        if status != ASTCENC_SUCCESS:
            return None, f"astcenc_decompress_image failed: {get_error_string(status)}"

        return bytes(decompressed_buffer), None
    finally:
        astcenc.astcenc_context_free(context)
        if decompressed_buffer:
            del decompressed_buffer


ASTC_FORMATS = {
    47: (4, 4), 48: (4, 4), 49: (5, 5), 50: (6, 6),
    51: (8, 8), 52: (10, 10), 53: (12, 12),
}

def unpack_bundle(bundle_path, output_dir, progress_callback=print):
    progress_callback(f"Starting to unpack '{os.path.basename(bundle_path)}'...")
    
    if not os.path.exists(bundle_path):
        return (False, f"Bundle file not found at '{bundle_path}'")

    os.makedirs(output_dir, exist_ok=True)
    progress_callback(f"Output directory '{output_dir}' is ready.")
    
    TypeTreeHelper.read_typetree_boost = False
    UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'
    
    env = None
    try:
        env = UnityPy.load(bundle_path)
        total_objects = len(env.objects)
        progress_callback(f"Successfully loaded bundle. Found {total_objects} assets.")

        for i, obj in enumerate(env.objects):
            data = None
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
                    
                    img = None
                    try:
                        img = data.image
                        img.save(dest_path)
                    finally:
                        if img:
                            del img

                elif obj.type.name in ["TextAsset", "MonoBehaviour"]:
                    # Handle both TextAsset and MonoBehaviour .skel files
                    if obj.type.name == "MonoBehaviour" and ".skel" not in dest_name.lower():
                        continue # Skip non-skel MonoBehaviours
                    
                    with open(dest_path, "wb") as f:
                        content = data.m_Script
                        if isinstance(content, str):
                            content = content.encode('utf-8', 'surrogateescape')
                        f.write(content)

            except Exception as e:
                import traceback
                asset_name = "Unknown"
                if data and hasattr(data, 'm_Name'):
                    asset_name = data.m_Name
                error_message = f"FAILED to export asset '{asset_name}': {e}"
                progress_callback(error_message)
                print(traceback.format_exc())
            finally:
                if data:
                    del data
                gc.collect() 
        
        progress_callback("Unpacking complete.")
        return (True, "Unpacking complete.")
        
    except Exception as e:
        import traceback
        error_message = f"Failed to load bundle: {e}"
        progress_callback(error_message)
        print(traceback.format_exc())
        return (False, error_message)
    finally:
        if env:
            del env
        gc.collect()
