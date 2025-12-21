from PIL import Image
import os
import ctypes
from ctypes import *
from .json_to_skel import json_to_skel
import tempfile
import gc
import re
import shutil
import glob

from UnityPy.helpers import TypeTreeHelper
TypeTreeHelper.read_typetree_boost = False
import UnityPy

UnityPy.config.FALLBACK_UNITY_VERSION = '2022.3.22f1'

_astcenc_lib = None
_astcenc_structures = {}

def _load_astcenc_library():
    global _astcenc_lib
    if _astcenc_lib is not None:
        return _astcenc_lib if _astcenc_lib else None

    try:
        lib = ctypes.cdll.LoadLibrary("libastcenc.so")
    except OSError as e:
        print(f"FATAL: Could not load libastcenc.so. Make sure it's in jniLibs. Error: {e}")
        _astcenc_lib = False
        return None

    _astcenc_structures['ASTCENC_SUCCESS'] = 0
    _astcenc_structures['ASTCENC_PRF_LDR_SRGB'] = 0
    _astcenc_structures['ASTCENC_PRE_MEDIUM'] = 60.0
    _astcenc_structures['ASTCENC_TYPE_U8'] = 0
    _astcenc_structures['ASTCENC_FLG_USE_DECODE_UNORM8'] = 1 << 1

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

from utils.atlas_operations import parse_atlas_file

def _merge_spine_assets(mod_dir_path, base_name, target_count, report_progress):
    report_progress(f"Starting Spine asset merge for '{base_name}' in temporary directory.")

    original_png_files = sorted(glob.glob(os.path.join(mod_dir_path, f'{base_name}*.png')))
    original_atlas_path = os.path.join(mod_dir_path, f"{base_name}.atlas")

    if not original_png_files or not os.path.exists(original_atlas_path):
        return f"SKIPPING: Missing png or atlas files for {base_name} in the working directory."

    atlas_db, err = parse_atlas_file(original_atlas_path)
    if err:
        return f"FAILED: Could not parse atlas file: {err}"

    # Sort pngs correctly (_2 before _10)
    def sort_key(filename):
        if filename.endswith(f"{base_name}.png"): return 1
        match = re.search(r'_(\d+)\.png$', filename)
        return int(match.group(1)) if match else float('inf')
    original_png_files.sort(key=lambda x: sort_key(os.path.basename(x)))

    if len(original_png_files) <= target_count:
        # This case should be handled by the calling function, but as a safeguard:
        return "Merge not needed, texture count is already at or below target."

    report_progress(f"Merging {len(original_png_files)} textures down to {target_count}.")

    images = {os.path.basename(p): Image.open(p) for p in original_png_files}
    
    merging_plan = {i: [] for i in range(target_count)}
    for i, png_path in enumerate(original_png_files):
        merging_plan[i % target_count].append(os.path.basename(png_path))
    
    final_atlas_blocks = []

    for i in range(target_count):
        target_pngs = merging_plan[i]
        if not target_pngs: continue

        base_image_name = target_pngs[0]
        base_image = images[base_image_name].copy()
        
        merged_offsets = {base_image_name: (0, 0)}
        
        current_width = base_image.width
        max_height = base_image.height
        
        for extra_image_name in target_pngs[1:]:
            extra_image = images[extra_image_name]
            merged_offsets[extra_image_name] = (current_width, 0)
            
            new_width = current_width + extra_image.width
            max_height = max(max_height, extra_image.height)
            
            new_base_image = Image.new('RGBA', (new_width, max_height))
            new_base_image.paste(base_image, (0, 0))
            new_base_image.paste(extra_image, (current_width, 0))
            
            # Clean up old base_image to free memory
            base_image.close()
            base_image = new_base_image
            current_width = new_width

        output_image_name = f"{base_name}.png" if i == 0 else f"{base_name}_{i+1}.png"
        output_path = os.path.join(mod_dir_path, output_image_name)
        base_image.save(output_path)
        base_image.close()  # Close after saving
        report_progress(f"Saved merged image: {output_path}")

        all_sprites_for_block = []
        for original_png_name in target_pngs:
            atlas_data = atlas_db.get(original_png_name)
            if not atlas_data: continue

            offset_x, offset_y = merged_offsets[original_png_name]
            
            if offset_x == 0 and offset_y == 0:
                all_sprites_for_block.append(atlas_data['sprites'])
            else:
                modified_sprites = []
                for line in atlas_data['sprites'].split('\n'):
                    if line.strip().startswith('bounds:'):
                        try:
                            parts = line.strip().split(':')
                            coords = parts[1].strip().split(',')
                            x, y, w, h = [int(c.strip()) for c in coords]
                            x += offset_x
                            y += offset_y
                            modified_sprites.append(f" bounds: {x},{y},{w},{h}")
                        except: modified_sprites.append(line)
                    else: modified_sprites.append(line)
                all_sprites_for_block.append('\n'.join(modified_sprites))

        filter_line = atlas_db[target_pngs[0]]['filter_line']
        new_block = [
            output_image_name,
            f" size: {base_image.width},{base_image.height}",
            filter_line,
            '\n'.join(all_sprites_for_block)
        ]
        final_atlas_blocks.append('\n'.join(new_block))
    
    final_atlas_path = os.path.join(mod_dir_path, f"{base_name}.atlas")
    with open(final_atlas_path, 'w', encoding='utf-8') as f:
        f.write('\n\n'.join(final_atlas_blocks))
    report_progress(f"Wrote final atlas file to: {final_atlas_path}")
    
    # Clean up images dict to free memory
    for img in images.values():
        img.close()
    del images
    
    # Clean up old, unmerged png files
    report_progress("Cleaning up original, unmerged texture files...")
    for png_path in original_png_files:
        try:
            # Check if the file is one of the NEWLY created merged files.
            # If so, don't delete it.
            if os.path.basename(png_path) not in [f"{base_name}.png" if i == 0 else f"{base_name}_{i+1}.png" for i in range(target_count)]:
                os.remove(png_path)
        except OSError as e:
            report_progress(f"Could not delete old file {png_path}: {e}")
            
    return None

from utils.file_operations import find_file_case_insensitive

def compress_image_astc(image_bytes, width, height, block_x, block_y):
    astcenc = _load_astcenc_library()
    if not astcenc:
        return None, "libastcenc.so could not be loaded."
        
    ASTCENC_SUCCESS = _astcenc_structures['ASTCENC_SUCCESS']
    ASTCENC_PRF_LDR_SRGB = _astcenc_structures['ASTCENC_PRF_LDR_SRGB']
    ASTCENC_PRE_MEDIUM = _astcenc_structures['ASTCENC_PRE_MEDIUM']
    ASTCENC_TYPE_U8 = _astcenc_structures['ASTCENC_TYPE_U8']
    ASTCENC_FLG_USE_DECODE_UNORM8 = _astcenc_structures['ASTCENC_FLG_USE_DECODE_UNORM8']
    astcenc_config = _astcenc_structures['astcenc_config']
    astcenc_image = _astcenc_structures['astcenc_image']
    astcenc_swizzle = _astcenc_structures['astcenc_swizzle']

    config = astcenc_config()
    quality = ASTCENC_PRE_MEDIUM
    profile = ASTCENC_PRF_LDR_SRGB
    flags = ASTCENC_FLG_USE_DECODE_UNORM8
    status = astcenc.astcenc_config_init(profile, block_x, block_y, 1, quality, flags, byref(config))
    if status != ASTCENC_SUCCESS:
        return None, f"astcenc_config_init failed: {get_error_string(astcenc, status)}"

    context = c_void_p()
    thread_count = os.cpu_count() or 1
    status = astcenc.astcenc_context_alloc(byref(config), thread_count, byref(context))
    if status != ASTCENC_SUCCESS:
        return None, f"astcenc_context_alloc failed: {get_error_string(astcenc, status)}"

    image_data_p = (c_void_p * 1)()
    image_data_p[0] = ctypes.cast(image_bytes, c_void_p)

    image = astcenc_image()
    image.dim_x = width
    image.dim_y = height
    image.dim_z = 1
    image.data_type = ASTCENC_TYPE_U8
    image.data = image_data_p

    swizzle = astcenc_swizzle(r=0, g=1, b=2, a=3)

    blocks_x = (width + block_x - 1) // block_x
    blocks_y = (height + block_y - 1) // block_y
    buf_size = blocks_x * blocks_y * 16
    comp_buf = (c_ubyte * buf_size)()

    status = astcenc.astcenc_compress_image(context, byref(image), byref(swizzle), comp_buf, buf_size, 0)
    
    astcenc.astcenc_context_free(context)

    if status != ASTCENC_SUCCESS:
        return None, f"astcenc_compress_image failed: {get_error_string(astcenc, status)}"

    return bytes(comp_buf), None


def repack_bundle(original_bundle_path: str, modded_assets_folder: str, output_path: str, use_astc: bool, progress_callback=None):
    """
    Repack a unity bundle with modded assets.
    Returns a tuple: (success: bool, message: str)
    """
    def report_progress(message):
        if progress_callback:
            progress_callback(message)
        print(message)
        
    env = None
    temp_dir_obj = None
    try:
        temp_dir_obj = tempfile.TemporaryDirectory()
        working_dir = os.path.join(temp_dir_obj.name, "mod")
        report_progress(f"Using temporary directory: {working_dir}")
        shutil.copytree(modded_assets_folder, working_dir)

        report_progress("Loading original game file...")
        env = UnityPy.load(original_bundle_path)
        edited = False

        # --- Pre-processing Step: Scan for all unique spine mods and their paths ---
        spine_mods_to_process = {}  # base_name -> directory_path
        for root, _, files in os.walk(working_dir):
            if '.old' in root:
                continue
            for f in files:
                if f.lower().endswith(('.skel', '.json')):
                    base_name = os.path.splitext(f)[0]
                    if base_name not in spine_mods_to_process:
                        spine_mods_to_process[base_name] = root

        if spine_mods_to_process:
            report_progress(f"Detected {len(spine_mods_to_process)} unique Spine mods for pre-processing: {list(spine_mods_to_process.keys())}")
            for spine_base_name, mod_dir_path in spine_mods_to_process.items():
                report_progress(f"--- Processing: {spine_base_name} ---")
                pattern = re.compile(f"^{re.escape(spine_base_name)}(_\\d+)?$", re.IGNORECASE)

                original_texture_count = 0
                for obj in env.objects:
                    if obj.type.name == "Texture2D":
                        try:
                            asset_name = obj.read().m_Name
                            if pattern.match(asset_name):
                                original_texture_count += 1
                        except Exception as e:
                            report_progress(f"Couldn't read asset name, skipping. Error: {e}")

                report_progress(f"Found {original_texture_count} matching textures in the original game file for {spine_base_name}.")

                mod_texture_count = len(glob.glob(os.path.join(mod_dir_path, f'{spine_base_name}*.png')))
                report_progress(f"Mod has {mod_texture_count} textures for {spine_base_name}.")

                if mod_texture_count > original_texture_count and original_texture_count > 0:
                    report_progress("Mod texture count exceeds original, starting merge process into temp directory...")
                    merge_error = _merge_spine_assets(mod_dir_path, spine_base_name, original_texture_count, report_progress)

                    if merge_error:
                        report_progress(f"ERROR during merge for {spine_base_name}: {merge_error}.")
                    else:
                        report_progress(f"Merge successful for {spine_base_name}.")
                else:
                    report_progress("Texture count matches or is lower, no merge needed.")

        report_progress("Scanning for moddable assets...")
        
        # Build asset_map more efficiently
        total_objects = len(env.objects)
        asset_map = {}
        for obj in env.objects:
            try:
                data = obj.read()
                if hasattr(data, 'm_Name'):
                    asset_map[data.m_Name.lower()] = obj
            except Exception:
                pass  # Skip objects that can't be read

        mod_files = []
        for root, _, files in os.walk(working_dir):
            if '.old' in root:
                continue
            for f in files:
                mod_files.append(os.path.join(root, f))

        total_assets = len(mod_files)
        for i, mod_filepath in enumerate(mod_files):
            mod_filename = os.path.basename(mod_filepath)
            current_progress = f"({i+1}/{total_assets}) "

            try:
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
                    continue

                if mod_filename.lower().endswith('.png'):
                    target_asset_name = os.path.splitext(mod_filename)[0].lower()
                    if target_asset_name in asset_map:
                        obj = asset_map[target_asset_name]
                        if obj.type.name == "Texture2D":
                            report_progress(f"{current_progress}Replacing texture: {mod_filename}")
                            data = obj.read()
                            
                            # Use context manager equivalent for PIL Image
                            pil_img = None
                            try:
                                pil_img = Image.open(mod_filepath).convert("RGBA")

                                if use_astc:
                                    flipped_img = pil_img.transpose(Image.FLIP_TOP_BOTTOM)
                                    report_progress(f"{current_progress}Compressing texture to ASTC: {mod_filename}")
                                    block_x, block_y = 4, 4
                                    compressed_data, err = compress_image_astc(flipped_img.tobytes(), pil_img.width, pil_img.height, block_x, block_y)
                                    
                                    flipped_img.close()
                                    del flipped_img
                                    gc.collect()

                                    if err:
                                        report_progress(f"ERROR: ASTC compression failed for {mod_filename}: {err}")
                                        continue

                                    data.m_TextureFormat = 48
                                    data.image_data = compressed_data
                                    data.m_CompleteImageSize = len(compressed_data)
                                else:
                                    report_progress(f"{current_progress}Using uncompressed RGBA32 for texture: {mod_filename}")
                                    data.m_TextureFormat = 4
                                    data.image = pil_img

                                data.m_Width, data.m_Height = pil_img.size
                                data.m_MipCount = 1
                                data.m_StreamData.offset = 0
                                data.m_StreamData.size = 0
                                data.m_StreamData.path = ""
                                data.save()
                                edited = True
                            finally:
                                if pil_img:
                                    pil_img.close()
                                    del pil_img

                    continue

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
        
        del asset_map
        del mod_files
        gc.collect()

        if edited:
            report_progress("Saving modified game file...")
            os.makedirs(os.path.dirname(output_path), exist_ok=True)
            try:
                with open(output_path, "wb") as f:
                    env.file.save(f, packer="lz4")
                report_progress("Saved successfully!")
                return True, "Repack completed successfully."
            except Exception as e:
                error_msg = f"Error saving bundle: {e}"
                report_progress(error_msg)
                return False, error_msg
        else:
            error_msg = "No modifications were made. Check if your mod files match any assets in the bundle."
            report_progress(error_msg)
            return False, error_msg

    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        report_progress(f"Error processing bundle: {error_message}")
        return False, error_message
    finally:
        if env is not None:
            del env
        if temp_dir_obj:
            temp_dir_obj.cleanup()
        gc.collect()
