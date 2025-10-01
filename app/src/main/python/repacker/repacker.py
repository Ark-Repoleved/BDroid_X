from PIL import Image
import os
import ctypes
from ctypes import c_uint, c_float, c_void_p, c_char_p, c_size_t, c_int, POINTER, Structure, c_ubyte, byref

# The following two lines are crucial for running in Chaquopy
from UnityPy.helpers import TypeTreeHelper
TypeTreeHelper.read_typetree_boost = False
import UnityPy

# Configure UnityPy fallback version
UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'

# --- ctypes definitions for libastcenc ---

# Define enums and constants from astcenc.h
ASTCENC_SUCCESS = 0
ASTCENC_PRF_LDR_SRGB = 0
ASTCENC_PRE_MEDIUM = 60.0
ASTCENC_TYPE_U8 = 0
ASTCENC_FLG_USE_DECODE_UNORM8 = 1 << 1

# Define structures
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
        ("progress_callback", c_void_p), # Ignoring progress callback for now
    ]

class astcenc_image(Structure):
    _fields_ = [
        ("dim_x", c_uint),
        ("dim_y", c_uint),
        ("dim_z", c_uint),
        ("data_type", c_uint),
        ("data", POINTER(c_void_p)),
    ]

# Load the shared library
try:
    astcenc = ctypes.cdll.LoadLibrary("libastcenc.so")
except OSError as e:
    print(f"FATAL: Could not load libastcenc.so. Make sure it's in jniLibs. Error: {e}")
    astcenc = None

if astcenc:
    # Define function prototypes
    astcenc.astcenc_config_init.argtypes = [c_uint, c_uint, c_uint, c_uint, c_float, c_uint, POINTER(astcenc_config)]
    astcenc.astcenc_config_init.restype = c_int

    astcenc.astcenc_context_alloc.argtypes = [POINTER(astcenc_config), c_uint, POINTER(c_void_p)]
    astcenc.astcenc_context_alloc.restype = c_int

    astcenc.astcenc_compress_image.argtypes = [c_void_p, POINTER(astcenc_image), POINTER(astcenc_swizzle), POINTER(c_ubyte), c_size_t, c_uint]
    astcenc.astcenc_compress_image.restype = c_int

    astcenc.astcenc_context_free.argtypes = [c_void_p]
    astcenc.astcenc_context_free.restype = None

    astcenc.astcenc_get_error_string.argtypes = [c_int]
    astcenc.astcenc_get_error_string.restype = c_char_p

def get_error_string(status):
    return astcenc.astcenc_get_error_string(status).decode('utf-8')

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
    if not astcenc:
        return None, "libastcenc.so not loaded."

    # 1. Initialize config
    config = astcenc_config()
    quality = ASTCENC_PRE_MEDIUM
    profile = ASTCENC_PRF_LDR_SRGB
    # IMPORTANT: Add flags based on reference script
    flags = ASTCENC_FLG_USE_DECODE_UNORM8
    status = astcenc.astcenc_config_init(profile, block_x, block_y, 1, quality, flags, byref(config))
    if status != ASTCENC_SUCCESS:
        return None, f"astcenc_config_init failed: {get_error_string(status)}"

    # 2. Allocate context
    context = c_void_p()
    thread_count = 1
    status = astcenc.astcenc_context_alloc(byref(config), thread_count, byref(context))
    if status != ASTCENC_SUCCESS:
        return None, f"astcenc_context_alloc failed: {get_error_string(status)}"

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
        return None, f"astcenc_compress_image failed: {get_error_string(status)}"

    return bytes(comp_buf), None


def repack_bundle(original_bundle_path: str, modded_assets_folder: str, output_path: str):
    """Repacks a Unity bundle with assets from a specified mod folder."""
    try:
        env = UnityPy.load(original_bundle_path)
        edited = False

        for obj in env.objects:
            try:
                if obj.type.name == "Texture2D":
                    data = obj.read()
                    file_name = data.m_Name + ".png"
                    
                    modded_file_path = find_modded_asset(modded_assets_folder, file_name)
                    
                    if modded_file_path:
                        print(f"Replacing Texture2D: {data.m_Name} in {os.path.basename(original_bundle_path)}")

                        pil_img = Image.open(modded_file_path).convert("RGBA")
                        
                        # IMPORTANT: Flip the image vertically to match OpenGL/Unity coordinate system
                        flipped_img = pil_img.transpose(Image.FLIP_TOP_BOTTOM)

                        # Match block size from reference script
                        block_x, block_y = 4, 4
                        
                        # Compress the image to ASTC
                        compressed_data, err = compress_image_astc(flipped_img.tobytes(), pil_img.width, pil_img.height, block_x, block_y)

                        if err:
                            print(f"ERROR: ASTC compression failed for {file_name}: {err}")
                            continue

                        # Update Texture2D object with compressed data
                        data.m_Width, data.m_Height = pil_img.size
                        # 48 is the integer value for ASTC_4x4_UNORM_SRGB
                        data.m_TextureFormat = 48
                        data.image_data = compressed_data
                        data.m_CompleteImageSize = len(compressed_data)
                        data.m_MipCount = 1
                        data.m_StreamData.offset = 0
                        data.m_StreamData.size = 0
                        data.m_StreamData.path = ""

                        data.save()
                        edited = True

                elif obj.type.name == "TextAsset":
                    data = obj.read()
                    file_name = data.m_Name
                    
                    modded_file_path = find_modded_asset(modded_assets_folder, file_name)

                    if modded_file_path:
                        print(f"Replacing TextAsset: {file_name} in {os.path.basename(original_bundle_path)}")
                        with open(modded_file_path, "rb") as f:
                            data.m_Script = f.read().decode("utf-8", "surrogateescape")
                        data.save()
                        edited = True

            except Exception as e:
                import traceback
                print(f"Error processing asset in {os.path.basename(original_bundle_path)}: {traceback.format_exc()}")

        if edited:
            os.makedirs(os.path.dirname(output_path), exist_ok=True)
            try:
                with open(output_path, "wb") as f:
                    bundle_data = env.file.save(packer="lz4")
                    f.write(bundle_data)
                print(f"Saved modified bundle to {output_path}")
                return True
            except Exception as e:
                print(f"Error saving bundle: {e}")
                return False
        else:
            print("No modifications were made.")
            return False

    except Exception as e:
        import traceback
        print(f"Error processing bundle {os.path.basename(original_bundle_path)}: {traceback.format_exc()}")
        return False