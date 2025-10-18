from PIL import Image
import os
import ctypes
from ctypes import *
from .json_to_skel import json_to_skel
import tempfile

# The following two lines are crucial for running in Chaquopy
from UnityPy.helpers import TypeTreeHelper
TypeTreeHelper.read_typetree_boost = False
import UnityPy

# Configure UnityPy fallback version
UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'

# --- Lazy-loaded ASTC library and definitions ---
_astcenc_lib = None
_astcenc_structures = {}

def _load_astcenc_library():
    """
    Loads the astcenc library dynamically, defines ctypes structures, 
    and sets function prototypes. This ensures the heavy library is only
    loaded into memory when absolutely needed.
    """
    global _astcenc_lib
    if _astcenc_lib is not None:
        # Return cached library object, or None if previous load failed
        return _astcenc_lib if _astcenc_lib else None

    try:
        lib = ctypes.cdll.LoadLibrary("libastcenc.so")
    except OSError as e:
        print(f"FATAL: Could not load libastcenc.so. Make sure it's in jniLibs. Error: {e}")
        _astcenc_lib = False  # Mark as failed
        return None

    # Define enums and constants
    _astcenc_structures['ASTCENC_SUCCESS'] = 0
    _astcenc_structures['ASTCENC_PRF_LDR_SRGB'] = 0
    _astcenc_structures['ASTCENC_PRE_MEDIUM'] = 60.0
    _astcenc_structures['ASTCENC_TYPE_U8'] = 0
    _astcenc_structures['ASTCENC_FLG_USE_DECODE_UNORM8'] = 1 << 1

    # Define structures
    class astcenc_swizzle(Structure):
        _fields_ = [("r", c_uint), ("g", c_uint), ("b", c_uint), ("a", c_uint)]
    _astcenc_structures['astcenc_swizzle'] = astcenc_swizzle

    class astcenc_config(Structure):
        _fields_ = [
            ("profile", c_uint), ("flags", c_uint), ("block_x", c_uint),
            ("block_y", c_uint), ("block_z", c_uint), ("cw_r_weight", c_float),
            ("cw_g_weight", c_float), ("cw_b_weight", c_float), ("cw_a_weight", c_float),
            ("a_scale_radius", c_uint), ("rgbm_m_scale", c_float),
            ("tune_partition_count_limit", c_uint), ("tune_2partition_index_limit", c_uint),
            ("tune_3partition_index_limit", c_uint), ("tune_4partition_index_limit", c_uint),
            ("tune_block_mode_limit", c_uint), ("tune_refinement_limit", c_uint),
            ("tune_candidate_limit", c_uint), ("tune_2partitioning_candidate_limit", c_uint),
            ("tune_3partitioning_candidate_limit", c_uint), ("tune_4partitioning_candidate_limit", c_uint),
            ("tune_db_limit", c_float), ("tune_mse_overshoot", c_float),
            ("tune_2partition_early_out_limit_factor", c_float), ("tune_3partition_early_out_limit_factor", c_float),
            ("tune_2plane_early_out_limit_correlation", c_float), ("tune_search_mode0_enable", c_float),
            ("progress_callback", c_void_p),
        ]
    _astcenc_structures['astcenc_config'] = astcenc_config

    class astcenc_image(Structure):
        _fields_ = [
            ("dim_x", c_uint), ("dim_y", c_uint), ("dim_z", c_uint),
            ("data_type", c_uint), ("data", POINTER(c_void_p)),
        ]
    _astcenc_structures['astcenc_image'] = astcenc_image
    
    # Define function prototypes
    lib.astcenc_config_init.argtypes = [c_uint, c_uint, c_uint, c_uint, c_float, c_uint, POINTER(astcenc_config)]
    lib.astcenc_config_init.restype = c_int

    lib.astcenc_context_alloc.argtypes = [POINTER(astcenc_config), c_uint, POINTER(c_void_p)]
    lib.astcenc_context_alloc.restype = c_int

    lib.astcenc_compress_image.argtypes = [c_void_p, POINTER(astcenc_image), POINTER(astcenc_swizzle), POINTER(c_ubyte), c_size_t, c_uint]
    lib.astcenc_compress_image.restype = c_int

    lib.astcenc_context_free.argtypes = [c_void_p]
    lib.astcenc_context_free.restype = None

    lib.astcenc_get_error_string.argtypes = [c_int]
    lib.astcenc_get_error_string.restype = c_char_p

    _astcenc_lib = lib
    return _astcenc_lib

def get_error_string(astcenc_lib, status):
    return astcenc_lib.astcenc_get_error_string(status).decode('utf-8')

def find_modded_asset(folder: str, filename: str) -> str:
    """Recursively search for a file in a directory, ignoring case."""
    search_filename_lower = filename.lower()
    for root, dirs, files in os.walk(folder):
        for f in files:
            if f.lower() == search_filename_lower:
                return os.path.join(root, f)
    return None

def compress_image_astc(image_bytes, width, height, block_x, block_y):
    """Compresses an RGBA image using libastcenc."""
    astcenc = _load_astcenc_library()
    if not astcenc:
        return None, "libastcenc.so could not be loaded."
        
    # Get constants and structures from the loaded library context
    ASTCENC_SUCCESS = _astcenc_structures['ASTCENC_SUCCESS']
    ASTCENC_PRF_LDR_SRGB = _astcenc_structures['ASTCENC_PRF_LDR_SRGB']
    ASTCENC_PRE_MEDIUM = _astcenc_structures['ASTCENC_PRE_MEDIUM']
    ASTCENC_TYPE_U8 = _astcenc_structures['ASTCENC_TYPE_U8']
    ASTCENC_FLG_USE_DECODE_UNORM8 = _astcenc_structures['ASTCENC_FLG_USE_DECODE_UNORM8']
    astcenc_config = _astcenc_structures['astcenc_config']
    astcenc_image = _astcenc_structures['astcenc_image']
    astcenc_swizzle = _astcenc_structures['astcenc_swizzle']

    # 1. Initialize config
    config = astcenc_config()
    quality = ASTCENC_PRE_MEDIUM
    profile = ASTCENC_PRF_LDR_SRGB
    flags = ASTCENC_FLG_USE_DECODE_UNORM8
    status = astcenc.astcenc_config_init(profile, block_x, block_y, 1, quality, flags, byref(config))
    if status != ASTCENC_SUCCESS:
        return None, f"astcenc_config_init failed: {get_error_string(astcenc, status)}"

    # 2. Allocate context
    context = c_void_p()
    thread_count = os.cpu_count() or 1
    status = astcenc.astcenc_context_alloc(byref(config), thread_count, byref(context))
    if status != ASTCENC_SUCCESS:
        return None, f"astcenc_context_alloc failed: {get_error_string(astcenc, status)}"

    # 3. Prepare image structure
    image_data_p = (c_void_p * 1)()
    image_data_p[0] = ctypes.cast(image_bytes, c_void_p)

    image = astcenc_image()
    image.dim_x = width
    image.dim_y = height
    image.dim_z = 1
    image.data_type = ASTCENC_TYPE_U8
    image.data = image_data_p

    # 4. Prepare swizzle
    swizzle = astcenc_swizzle(r=0, g=1, b=2, a=3) # R, G, B, A

    # 5. Prepare output buffer
    blocks_x = (width + block_x - 1) // block_x
    blocks_y = (height + block_y - 1) // block_y
    buf_size = blocks_x * blocks_y * 16
    comp_buf = (c_ubyte * buf_size)()

    # 6. Compress
    status = astcenc.astcenc_compress_image(context, byref(image), byref(swizzle), comp_buf, buf_size, 0)
    
    # 7. Free context
    astcenc.astcenc_context_free(context)

    if status != ASTCENC_SUCCESS:
        return None, f"astcenc_compress_image failed: {get_error_string(astcenc, status)}"

    return bytes(comp_buf), None


def repack_bundle(original_bundle_path: str, modded_assets_folder: str, output_path: str, use_astc: bool, progress_callback=None):
    """Repacks a Unity bundle with assets from a specified mod folder."""
    def report_progress(message):
        if progress_callback:
            progress_callback(message)
        print(message)

    try:
        report_progress("Loading original game file...")
        env = UnityPy.load(original_bundle_path)
        edited = False

        report_progress("Scanning for moddable assets...")
        
        # Create a map of all assets in the bundle for quick lookup
        asset_map = {obj.read().m_Name.lower(): obj for obj in env.objects if hasattr(obj.read(), 'm_Name')}

        # Find all mod files first
        mod_files = []
        for root, _, files in os.walk(modded_assets_folder):
            for f in files:
                mod_files.append(os.path.join(root, f))

        total_assets = len(mod_files)
        for i, mod_filepath in enumerate(mod_files):
            mod_filename = os.path.basename(mod_filepath)
            current_progress = f"({i+1}/{total_assets}) "

            try:
                # Handle JSON to SKEL conversion
                if mod_filename.lower().endswith('.json'):
                    base_name, _ = os.path.splitext(mod_filename)
                    target_asset_name = (base_name + ".skel").lower()
                    
                    if target_asset_name in asset_map:
                        report_progress(f"{current_progress}Converting and replacing animation: {mod_filename}")
                        obj = asset_map[target_asset_name]
                        data = obj.read()

                        with tempfile.NamedTemporaryFile(delete=False, suffix=".skel") as tmp_skel_file:
                            temp_skel_path = tmp_skel_file.name
                        
                        try:
                            json_to_skel(mod_filepath, temp_skel_path)
                            with open(temp_skel_path, 'rb') as f:
                                skel_binary_data = f.read()
                            
                            data.m_Script = skel_binary_data.decode("utf-8", "surrogateescape")
                            data.save()
                            edited = True
                            report_progress(f"{current_progress}Successfully replaced animation for {mod_filename}")
                        finally:
                            if os.path.exists(temp_skel_path):
                                os.remove(temp_skel_path)
                    continue # Move to next file

                # Handle Texture2D
                if mod_filename.lower().endswith('.png'):
                    target_asset_name = os.path.splitext(mod_filename)[0].lower()
                    if target_asset_name in asset_map:
                        obj = asset_map[target_asset_name]
                        if obj.type.name == "Texture2D":
                            report_progress(f"{current_progress}Replacing texture: {mod_filename}")
                            data = obj.read()
                            pil_img = Image.open(mod_filepath).convert("RGBA")

                            if use_astc:
                                flipped_img = pil_img.transpose(Image.FLIP_TOP_BOTTOM)
                                report_progress(f"{current_progress}Compressing texture to ASTC: {mod_filename}")
                                block_x, block_y = 4, 4
                                compressed_data, err = compress_image_astc(flipped_img.tobytes(), pil_img.width, pil_img.height, block_x, block_y)

                                if err:
                                    report_progress(f"ERROR: ASTC compression failed for {mod_filename}: {err}")
                                    continue

                                data.m_TextureFormat = 48 # ASTC_4x4
                                data.image_data = compressed_data
                                data.m_CompleteImageSize = len(compressed_data)
                            else:
                                report_progress(f"{current_progress}Using uncompressed RGBA32 for texture: {mod_filename}")
                                data.m_TextureFormat = 4 # RGBA32
                                data.image = pil_img

                            data.m_Width, data.m_Height = pil_img.size
                            data.m_MipCount = 1
                            data.m_StreamData.offset = 0
                            data.m_StreamData.size = 0
                            data.m_StreamData.path = ""
                            data.save()
                            edited = True
                    continue

                # Handle other TextAssets (like .atlas)
                target_asset_name = mod_filename.lower()
                if target_asset_name in asset_map:
                    obj = asset_map[target_asset_name]
                    if obj.type.name == "TextAsset":
                        report_progress(f"{current_progress}Replacing asset: {mod_filename}")
                        data = obj.read()
                        with open(mod_filepath, "rb") as f:
                            data.m_Script = f.read().decode("utf-8", "surrogateescape")
                        data.save()
                        edited = True

            except Exception as e:
                import traceback
                report_progress(f"Error processing asset {mod_filename}: {traceback.format_exc()}")

        if edited:
            report_progress("Saving modified game file...")
            os.makedirs(os.path.dirname(output_path), exist_ok=True)
            try:
                with open(output_path, "wb") as f:
                    bundle_data = env.file.save(packer="lz4")
                    f.write(bundle_data)
                report_progress("Saved successfully!")
                return True
            except Exception as e:
                report_progress(f"Error saving bundle: {e}")
                return False
        else:
            report_progress("No modifications were made.")
            return False

    except Exception as e:
        import traceback
        message = f"Error processing bundle: {traceback.format_exc()}"
        report_progress(message)
        return False
