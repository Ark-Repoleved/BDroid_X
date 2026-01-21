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
from concurrent.futures import ThreadPoolExecutor, ProcessPoolExecutor, as_completed
import sys

# 平行處理設定
# 限制同時處理的紋理數量，避免記憶體過載
MAX_PARALLEL_TEXTURES = min(4, max(1, (os.cpu_count() or 4) // 2))

# 檢測是否在 Android 環境 (Chaquopy)
# Android 上 ProcessPoolExecutor 可能無法正常工作，改用 ThreadPoolExecutor
IS_ANDROID = hasattr(sys, 'getandroidapilevel') or 'ANDROID_ROOT' in os.environ

# GPU ASTC 壓縮支援
_gpu_astc_checked = False
_gpu_astc_available = False

def _check_gpu_astc():
    """檢查 GPU ASTC 壓縮是否可用"""
    global _gpu_astc_checked, _gpu_astc_available
    if _gpu_astc_checked:
        return _gpu_astc_available
    
    _gpu_astc_checked = True
    try:
        from .gpu_astc import is_gpu_available
        _gpu_astc_available = is_gpu_available()
        print(f"[Repacker] GPU ASTC compression available: {_gpu_astc_available}")
    except Exception as e:
        print(f"[Repacker] GPU ASTC check failed: {e}")
        _gpu_astc_available = False
    
    return _gpu_astc_available

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


def _build_file_index(working_dir: str) -> dict:
    """
    一次遍歷建立完整的檔案索引，避免重複 os.walk 操作。
    
    Returns:
        dict with keys:
        - 'skel_json': {base_name: (type, filepath, directory)}
        - 'all_files': [filepath, ...]
    """
    index = {
        'skel_json': {},   # base_name -> (type, path, directory)
        'all_files': []    # [path]
    }
    
    for root, _, files in os.walk(working_dir):
        if '.old' in root:
            continue
        for f in files:
            filepath = os.path.join(root, f)
            index['all_files'].append(filepath)
            f_lower = f.lower()
            
            if f_lower.endswith(('.skel', '.json')):
                base_name = os.path.splitext(f)[0]
                file_type = 'skel' if f_lower.endswith('.skel') else 'json'
                if base_name not in index['skel_json']:
                    index['skel_json'][base_name] = (file_type, filepath, root)
    
    return index


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


def _compress_texture_worker_gpu(args):
    """
    Worker 函數，用於使用 GPU 執行 ASTC 壓縮。
    
    Args:
        args: tuple of (mod_filepath, target_asset_name, block_x, block_y)
    
    Returns:
        dict with keys: 'success', 'target_asset_name', 'mod_filepath', 
                       'compressed_data', 'width', 'height', 'error', 'used_gpu'
    """
    mod_filepath, target_asset_name, block_x, block_y = args
    result = {
        'success': False,
        'target_asset_name': target_asset_name,
        'mod_filepath': mod_filepath,
        'compressed_data': None,
        'width': 0,
        'height': 0,
        'error': None,
        'used_gpu': True
    }
    
    pil_img = None
    try:
        # 先開啟圖片獲取尺寸
        pil_img = Image.open(mod_filepath)
        result['width'] = pil_img.width
        result['height'] = pil_img.height
        pil_img.close()
        pil_img = None
        
        # 使用 GPU 壓縮（不需要預翻轉，shader 會處理）
        # 注意：調用者必須已經持有 GPU 鎖
        from .gpu_astc import compress_with_gpu_locked
        import tempfile
        
        # 創建臨時輸出檔案
        with tempfile.NamedTemporaryFile(suffix='.astc', delete=False) as tmp_out:
            tmp_output_path = tmp_out.name
        
        try:
            # GPU 壓縮（flipY=True 讓 shader 處理翻轉）
            # 使用 locked 版本，因為調用者已經持有 GPU 鎖
            success = compress_with_gpu_locked(mod_filepath, tmp_output_path, flip_y=True)
            
            if success:
                with open(tmp_output_path, 'rb') as f:
                    result['compressed_data'] = f.read()
                result['success'] = True
            else:
                result['error'] = "GPU compression returned false"
        finally:
            if os.path.exists(tmp_output_path):
                os.remove(tmp_output_path)
                
    except Exception as e:
        import traceback
        result['error'] = f"GPU compression exception: {str(e)}"
        print(f"[GPU ASTC] Exception: {traceback.format_exc()}")
    finally:
        if pil_img:
            pil_img.close()
            del pil_img
        gc.collect()
    
    return result


def _compress_texture_worker_cpu(args):
    """
    Worker 函數，用於使用 CPU (libastcenc) 執行 ASTC 壓縮。
    
    Args:
        args: tuple of (mod_filepath, target_asset_name, block_x, block_y)
    
    Returns:
        dict with keys: 'success', 'target_asset_name', 'mod_filepath', 
                       'compressed_data', 'width', 'height', 'error', 'used_gpu'
    """
    mod_filepath, target_asset_name, block_x, block_y = args
    result = {
        'success': False,
        'target_asset_name': target_asset_name,
        'mod_filepath': mod_filepath,
        'compressed_data': None,
        'width': 0,
        'height': 0,
        'error': None,
        'used_gpu': False
    }
    
    pil_img = None
    flipped_img = None
    try:
        pil_img = Image.open(mod_filepath).convert("RGBA")
        result['width'] = pil_img.width
        result['height'] = pil_img.height
        
        flipped_img = pil_img.transpose(Image.FLIP_TOP_BOTTOM)
        
        compressed_data, err = compress_image_astc(
            flipped_img.tobytes(), 
            pil_img.width, 
            pil_img.height, 
            block_x, 
            block_y
        )
        
        if err:
            result['error'] = err
        else:
            result['success'] = True
            result['compressed_data'] = compressed_data
            
    except Exception as e:
        result['error'] = str(e)
    finally:
        if flipped_img:
            flipped_img.close()
            del flipped_img
        if pil_img:
            pil_img.close()
            del pil_img
        gc.collect()
    
    return result


def _compress_texture_worker(args):
    """
    Worker 函數，自動選擇 GPU 或 CPU 執行 ASTC 壓縮。
    優先使用 GPU，失敗時自動 fallback 到 CPU。
    
    Args:
        args: tuple of (mod_filepath, target_asset_name, block_x, block_y)
    
    Returns:
        dict with keys: 'success', 'target_asset_name', 'mod_filepath', 
                       'compressed_data', 'width', 'height', 'error', 'used_gpu'
    """
    # 檢查 GPU 是否可用
    if _check_gpu_astc():
        # 嘗試 GPU 壓縮
        result = _compress_texture_worker_gpu(args)
        if result['success']:
            return result
        
        # GPU 失敗，fallback 到 CPU
        print(f"[Repacker] GPU compression failed for {os.path.basename(args[0])}, falling back to CPU")
    
    # 使用 CPU 壓縮
    return _compress_texture_worker_cpu(args)


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
    try:
        # 直接使用 Kotlin 層已準備好的 mod 目錄，避免重複複製
        working_dir = modded_assets_folder
        report_progress(f"Using mod directory: {working_dir}")

        report_progress("Loading original game file...")
        env = UnityPy.load(original_bundle_path)
        edited = False

        # --- 建立檔案索引，一次遍歷取代多次 os.walk ---
        report_progress("Building file index...")
        file_index = _build_file_index(working_dir)
        
        # 從索引中取得 spine mods
        spine_mods_to_process = {
            base_name: directory 
            for base_name, (file_type, filepath, directory) in file_index['skel_json'].items()
        }

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
                        # 合併後需要重新建立索引以包含新檔案
                        file_index = _build_file_index(working_dir)
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

        # 直接使用索引中的檔案列表
        mod_files = file_index['all_files']
        total_assets = len(mod_files)
        
        # ========== 階段一：分類所有 mod 檔案 ==========
        report_progress("Phase 1: Categorizing mod files...")
        
        json_files = []      # JSON -> SKEL 轉換
        png_astc_files = []  # PNG -> ASTC 壓縮 (需平行處理)
        png_rgba_files = []  # PNG -> RGBA32 (不需壓縮)
        text_files = []      # TextAsset 替換
        
        for mod_filepath in mod_files:
            mod_filename = os.path.basename(mod_filepath)
            
            if mod_filename.lower().endswith('.json'):
                base_name, _ = os.path.splitext(mod_filename)
                target_asset_name = (base_name + ".skel").lower()
                if target_asset_name in asset_map:
                    json_files.append((mod_filepath, target_asset_name))
                    
            elif mod_filename.lower().endswith('.png'):
                target_asset_name = os.path.splitext(mod_filename)[0].lower()
                if target_asset_name in asset_map:
                    obj = asset_map[target_asset_name]
                    if obj.type.name == "Texture2D":
                        if use_astc:
                            png_astc_files.append((mod_filepath, target_asset_name))
                        else:
                            png_rgba_files.append((mod_filepath, target_asset_name))
            else:
                target_asset_name = mod_filename.lower()
                if target_asset_name in asset_map:
                    obj = asset_map[target_asset_name]
                    if obj.type.name == "TextAsset":
                        text_files.append((mod_filepath, target_asset_name))
        
        report_progress(f"  - JSON animations: {len(json_files)}")
        report_progress(f"  - ASTC textures: {len(png_astc_files)}")
        report_progress(f"  - RGBA32 textures: {len(png_rgba_files)}")
        report_progress(f"  - Text assets: {len(text_files)}")
        
        # ========== 階段二：處理 JSON 和 TextAsset (序列) ==========
        report_progress("Phase 2: Processing JSON and TextAsset files...")
        
        # 處理 JSON -> SKEL
        for i, (mod_filepath, target_asset_name) in enumerate(json_files):
            mod_filename = os.path.basename(mod_filepath)
            current_progress = f"(JSON {i+1}/{len(json_files)}) "
            
            try:
                report_progress(f"{current_progress}Converting animation: {mod_filename}")
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
                    report_progress(f"{current_progress}Successfully replaced: {mod_filename}")
                finally:
                    if os.path.exists(temp_skel_path):
                        os.remove(temp_skel_path)
                        
            except Exception as e:
                import traceback
                report_progress(f"Error processing {mod_filename}: {traceback.format_exc()}")
        
        # 處理 TextAsset
        for i, (mod_filepath, target_asset_name) in enumerate(text_files):
            mod_filename = os.path.basename(mod_filepath)
            current_progress = f"(Text {i+1}/{len(text_files)}) "
            
            try:
                report_progress(f"{current_progress}Replacing asset: {mod_filename}")
                obj = asset_map[target_asset_name]
                data = obj.read()
                with open(mod_filepath, "rb") as f:
                    data.m_Script = f.read().decode("utf-8", "surrogateescape")
                data.save()
                edited = True
                
            except Exception as e:
                import traceback
                report_progress(f"Error processing {mod_filename}: {traceback.format_exc()}")
        
        # ========== 階段三：ASTC 紋理壓縮 ==========
        # 策略：
        # - 嘗試取得 GPU 鎖，成功則使用 GPU 序列處理
        # - 取得失敗（GPU 被其他 job 佔用）則使用 CPU 並行處理
        # - 這樣可以讓 GPU 和 CPU 同時工作
        if png_astc_files:
            total_textures = len(png_astc_files)
            block_x, block_y = 4, 4
            total_success = 0
            total_failed = 0
            gpu_success_count = 0
            cpu_success_count = 0
            
            # 準備所有 worker 參數
            worker_args = [
                (mod_filepath, target_asset_name, block_x, block_y)
                for mod_filepath, target_asset_name in png_astc_files
            ]
            
            # 檢查 GPU 硬體是否支援
            gpu_supported = _check_gpu_astc()
            use_gpu = False
            gpu_lock_acquired = False
            
            if gpu_supported:
                # 嘗試取得 GPU 鎖（非阻塞）
                from .gpu_astc import try_acquire_gpu, release_gpu_lock
                gpu_lock_acquired = try_acquire_gpu()
                use_gpu = gpu_lock_acquired
                
                if not gpu_lock_acquired:
                    report_progress(f"Phase 3: CPU ASTC compression ({total_textures} textures, GPU busy - using parallel CPU)...")
            else:
                report_progress(f"Phase 3: CPU ASTC compression ({total_textures} textures, GPU not supported)...")
            
            # ========== GPU 處理模式（已取得鎖） ==========
            if gpu_lock_acquired:
                report_progress(f"Phase 3: GPU ASTC compression ({total_textures} textures, GPU lock acquired)...")
                gpu_failed_args = []  # 收集 GPU 失敗的紋理，稍後用 CPU 處理
                
                try:
                    for i, args in enumerate(worker_args):
                        mod_filepath, target_asset_name, bx, by = args
                        mod_filename = os.path.basename(mod_filepath)
                        
                        try:
                            result = _compress_texture_worker_gpu(args)
                            
                            if result['success']:
                                # 寫入 Bundle
                                obj = asset_map[target_asset_name]
                                data = obj.read()
                                
                                data.m_TextureFormat = 48  # ASTC_RGB_4x4
                                data.image_data = result['compressed_data']
                                data.m_CompleteImageSize = len(result['compressed_data'])
                                data.m_Width = result['width']
                                data.m_Height = result['height']
                                data.m_MipCount = 1
                                
                                if hasattr(data, 'm_StreamData'):
                                    data.m_StreamData.offset = 0
                                    data.m_StreamData.size = 0
                                    data.m_StreamData.path = ""
                                
                                data.save()
                                edited = True
                                total_success += 1
                                gpu_success_count += 1
                            else:
                                # GPU 失敗，加入待處理列表
                                gpu_failed_args.append(args)
                            
                            del result
                            
                        except Exception as e:
                            gpu_failed_args.append(args)
                        
                        # 定期報告進度
                        if (i + 1) % max(1, total_textures // 4) == 0 or i == total_textures - 1:
                            report_progress(f"  GPU Progress: {i+1}/{total_textures}")
                        
                        # 定期 GC
                        if (i + 1) % 20 == 0:
                            gc.collect()
                    
                    # 如果有 GPU 失敗的，用 CPU 並行處理
                    if gpu_failed_args:
                        report_progress(f"  {len(gpu_failed_args)} textures failed on GPU, falling back to CPU parallel processing...")
                        worker_args = gpu_failed_args
                    else:
                        worker_args = []  # 全部完成
                    
                    report_progress(f"  GPU completed: {gpu_success_count} success")
                    
                finally:
                    # 釋放 GPU 鎖，讓其他 job 可以使用（確保即使異常也釋放）
                    release_gpu_lock()
                    report_progress("  GPU lock released")
            
            # ========== CPU 並行處理模式 ==========
            # 用於 GPU 不可用，或 GPU 失敗的紋理
            if worker_args:
                executor_type = "Thread" if IS_ANDROID else "Process"
                cpu_textures = len(worker_args)
                
                if use_gpu:
                    # 這是 fallback 情況
                    report_progress(f"Phase 3 (CPU fallback): Processing remaining {cpu_textures} textures with {MAX_PARALLEL_TEXTURES} {executor_type} workers...")
                else:
                    report_progress(f"Phase 3: CPU ASTC compression ({cpu_textures} textures, {MAX_PARALLEL_TEXTURES} {executor_type} workers)...")
                
                completed_count = 0
                ExecutorClass = ThreadPoolExecutor if IS_ANDROID else ProcessPoolExecutor
                
                try:
                    with ExecutorClass(max_workers=MAX_PARALLEL_TEXTURES) as executor:
                        future_to_args = {
                            executor.submit(_compress_texture_worker_cpu, args): args 
                            for args in worker_args
                        }
                        
                        for future in as_completed(future_to_args):
                            completed_count += 1
                            args = future_to_args[future]
                            mod_filename = os.path.basename(args[0])
                            
                            try:
                                result = future.result()
                                
                                if result['success']:
                                    target_asset_name = result['target_asset_name']
                                    try:
                                        obj = asset_map[target_asset_name]
                                        data = obj.read()
                                        
                                        data.m_TextureFormat = 48
                                        data.image_data = result['compressed_data']
                                        data.m_CompleteImageSize = len(result['compressed_data'])
                                        data.m_Width = result['width']
                                        data.m_Height = result['height']
                                        data.m_MipCount = 1
                                        
                                        if hasattr(data, 'm_StreamData'):
                                            data.m_StreamData.offset = 0
                                            data.m_StreamData.size = 0
                                            data.m_StreamData.path = ""
                                        
                                        data.save()
                                        edited = True
                                        total_success += 1
                                        cpu_success_count += 1
                                        
                                    except Exception as e:
                                        total_failed += 1
                                        report_progress(f"  Write error {mod_filename}: {e}")
                                else:
                                    total_failed += 1
                                    report_progress(f"  FAILED: {mod_filename} - {result['error']}")
                                
                                del result
                                    
                            except Exception as e:
                                total_failed += 1
                                report_progress(f"  ERROR: {mod_filename} - {str(e)}")
                            
                            # 定期報告進度
                            if cpu_textures <= 10 or completed_count == cpu_textures or completed_count % max(1, cpu_textures // 4) == 0:
                                report_progress(f"  CPU Progress: {completed_count}/{cpu_textures}")
                            
                            if completed_count % 10 == 0:
                                gc.collect()
                                
                except Exception as e:
                    report_progress(f"Executor failed, falling back to sequential: {e}")
                    for args in worker_args:
                        result = _compress_texture_worker_cpu(args)
                        
                        if result['success']:
                            target_asset_name = result['target_asset_name']
                            try:
                                obj = asset_map[target_asset_name]
                                data = obj.read()
                                data.m_TextureFormat = 48
                                data.image_data = result['compressed_data']
                                data.m_CompleteImageSize = len(result['compressed_data'])
                                data.m_Width = result['width']
                                data.m_Height = result['height']
                                data.m_MipCount = 1
                                if hasattr(data, 'm_StreamData'):
                                    data.m_StreamData.offset = 0
                                    data.m_StreamData.size = 0
                                    data.m_StreamData.path = ""
                                data.save()
                                edited = True
                                total_success += 1
                                cpu_success_count += 1
                            except Exception as e:
                                total_failed += 1
                        else:
                            total_failed += 1
                        
                        del result
                        gc.collect()
            
            # 清理和最終報告
            del worker_args
            gc.collect()
            
            compression_method = "GPU" if gpu_success_count > 0 else "CPU"
            if gpu_success_count > 0 and cpu_success_count > 0:
                compression_method = f"GPU({gpu_success_count})+CPU({cpu_success_count})"
            report_progress(f"  ASTC compression complete ({compression_method}): {total_success} success, {total_failed} failed")
        
        # ========== 處理 RGBA32 紋理 (不需壓縮，序列處理) ==========
        if png_rgba_files:
            report_progress(f"Processing RGBA32 textures ({len(png_rgba_files)})...")
            
            for i, (mod_filepath, target_asset_name) in enumerate(png_rgba_files):
                mod_filename = os.path.basename(mod_filepath)
                current_progress = f"(RGBA {i+1}/{len(png_rgba_files)}) "
                
                pil_img = None
                try:
                    report_progress(f"{current_progress}Processing: {mod_filename}")
                    obj = asset_map[target_asset_name]
                    data = obj.read()
                    
                    pil_img = Image.open(mod_filepath).convert("RGBA")
                    
                    data.m_TextureFormat = 4  # RGBA32
                    data.image = pil_img
                    
                    data.m_MipCount = 1
                    
                    if hasattr(data, 'm_StreamData'):
                        data.m_StreamData.offset = 0
                        data.m_StreamData.size = 0
                        data.m_StreamData.path = ""
                    
                    data.save()
                    edited = True
                    
                except Exception as e:
                    import traceback
                    report_progress(f"Error processing {mod_filename}: {traceback.format_exc()}")
                finally:
                    if pil_img:
                        pil_img.close()
                        del pil_img
        
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
        gc.collect()
