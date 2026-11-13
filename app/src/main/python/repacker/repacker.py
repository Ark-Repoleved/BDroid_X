from PIL import Image, ImageFile
import os
import ctypes
from ctypes import *
from .json_to_skel import json_to_skel_bytes
import tempfile
import gc
import re
import shutil
import glob
from concurrent.futures import ThreadPoolExecutor, ProcessPoolExecutor, as_completed
import sys

ImageFile.LOAD_TRUNCATED_IMAGES = True

MAX_ATLAS_PAGE_DIM = 4096

_IGNORED_EXACT_NAMES = {
    '.ds_store', 'thumbs.db', 'desktop.ini', '.modfile',
    'mod.json', 'info.json', 'manifest.json', 'metadata.json', 'config.json',
    'readme.txt', 'readme.md', 'changelog.txt', 'changelog.md',
    'license.txt', 'license.md', 'credits.txt',
    'preview.png', 'cover.png', 'thumb.png', 'thumbnail.png',
}
_IGNORED_DIR_SEGMENTS = {'__macosx', '.git', '.old'}
_IGNORED_EXTENSIONS = (
    '.modfile', '.md', '.jpg', '.jpeg', '.webp', '.gif', '.bmp', '.url', '.lnk',
)

# 平行處理設定
# 限制同時處理的紋理數量，避免記憶體過載
MAX_PARALLEL_TEXTURES = min(4, max(1, (os.cpu_count() or 4) // 2))

# 檢測是否在 Android 環境 (Chaquopy)
# Android 上 ProcessPoolExecutor 可能無法正常工作，改用 ThreadPoolExecutor
IS_ANDROID = hasattr(sys, 'getandroidapilevel') or 'ANDROID_ROOT' in os.environ

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


def _ensure_temp_dir(working_dir: str, output_path: str) -> None:
    """Ensure tempfile has an existing writable directory (prevents Android cache cleanup Errno 2)."""
    current = tempfile.tempdir
    if current and os.path.isdir(current) and os.access(current, os.W_OK):
        return

    candidates = []
    env_tmp = os.environ.get('TMPDIR')
    if env_tmp:
        candidates.append(env_tmp)
    if output_path:
        candidates.append(os.path.join(os.path.dirname(output_path), '.tmp'))
    if working_dir:
        candidates.append(os.path.join(working_dir, '.tmp'))

    for candidate in candidates:
        if not candidate:
            continue
        try:
            os.makedirs(candidate, exist_ok=True)
            if os.path.isdir(candidate) and os.access(candidate, os.W_OK):
                tempfile.tempdir = candidate
                os.environ['TMPDIR'] = candidate
                return
        except OSError:
            continue


def _is_ignored_mod_file(filename: str, root: str = '') -> bool:
    norm_root = (root or '').replace('\\', '/')
    if any(seg.lower() in _IGNORED_DIR_SEGMENTS for seg in norm_root.split('/') if seg):
        return True
    lower = (filename or '').strip().lower()
    if not lower or lower in _IGNORED_EXACT_NAMES or lower.startswith('._'):
        return True
    if any(lower.endswith(ext) for ext in _IGNORED_EXTENSIONS):
        return True
    if lower.startswith('readme') and lower.endswith(('.txt', '.md')):
        return True
    return False


def _skel_base_name(filename: str) -> str:
    lower = filename.lower()
    for ext in ('.skel.bytes', '.skel.txt', '.skel', '.json'):
        if lower.endswith(ext):
            return filename[:-len(ext)]
    return os.path.splitext(filename)[0]


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

    for root, dirs, files in os.walk(working_dir):
        dirs[:] = [d for d in dirs if d.lower() not in _IGNORED_DIR_SEGMENTS]
        if _is_ignored_mod_file('dummy', root):
            continue
        for f in files:
            if _is_ignored_mod_file(f, root):
                continue
            filepath = os.path.join(root, f)
            index['all_files'].append(filepath)
            f_lower = f.lower()

            if f_lower.endswith(('.skel', '.skel.bytes', '.skel.txt', '.json')):
                base_name = _skel_base_name(f)
                file_type = 'json' if f_lower.endswith('.json') else 'skel'
                if base_name not in index['skel_json']:
                    index['skel_json'][base_name] = (file_type, filepath, root)

    return index


from utils.atlas_operations import parse_atlas_file, offset_and_scale_atlas_sprites


def _find_atlas_path(mod_dir_path: str, base_name: str):
    for ext in ('.atlas', '.atlas.txt'):
        exact = os.path.join(mod_dir_path, f"{base_name}{ext}")
        if os.path.exists(exact):
            return exact
    target_lowers = {f"{base_name}.atlas".lower(), f"{base_name}.atlas.txt".lower()}
    for f in os.listdir(mod_dir_path):
        if f.lower() in target_lowers:
            return os.path.join(mod_dir_path, f)
    return None


def _find_atlas_entry(atlas_db: dict, png_name: str):
    if png_name in atlas_db:
        return atlas_db[png_name]
    lowered = png_name.lower()
    for key, val in atlas_db.items():
        if key.lower() == lowered:
            return val
    return None


def _pack_shelf_2d(group_images: list, max_width: int = MAX_ATLAS_PAGE_DIM):
    """Arrange (name, PIL.Image) pairs onto a 2D shelf layout keeping width <= max_width."""
    placements = {}
    shelf_x = 0
    shelf_y = 0
    shelf_h = 0
    total_w = 0
    total_h = 0

    for name, img in group_images:
        w, h = img.size
        if shelf_x > 0 and shelf_x + w > max_width:
            shelf_y += shelf_h
            shelf_x = 0
            shelf_h = 0
        placements[name] = (shelf_x, shelf_y)
        shelf_x += w
        shelf_h = max(shelf_h, h)
        total_w = max(total_w, shelf_x)
        total_h = max(total_h, shelf_y + shelf_h)

    return total_w, total_h, placements


def _merge_spine_assets(mod_dir_path, base_name, target_count, report_progress, target_texture_names=None):
    report_progress(f"Starting Spine asset merge for '{base_name}' in temporary directory.")

    _png_pattern = re.compile(rf'^{re.escape(base_name)}(_\d+)?\.png$', re.IGNORECASE)
    original_png_files = [
        os.path.join(mod_dir_path, f) for f in os.listdir(mod_dir_path)
        if os.path.isfile(os.path.join(mod_dir_path, f)) and _png_pattern.match(f)
    ]
    original_atlas_path = _find_atlas_path(mod_dir_path, base_name)

    if not original_png_files or not original_atlas_path:
        return f"Missing png or atlas files for {base_name} in '{mod_dir_path}'."

    atlas_db, err = parse_atlas_file(original_atlas_path)
    if err or not atlas_db:
        return f"Could not parse atlas file: {err or 'empty atlas'}"

    # Sort pngs naturally (_2 before _10)
    def sort_key(filename):
        if filename.lower() == f"{base_name.lower()}.png":
            return (0, 0)
        match = re.search(r'_(\d+)\.png$', filename, re.IGNORECASE)
        return (1, int(match.group(1))) if match else (2, float('inf'))

    original_png_files.sort(key=lambda x: sort_key(os.path.basename(x)))

    if len(original_png_files) <= target_count:
        return None

    report_progress(f"Merging {len(original_png_files)} textures down to {target_count}.")

    images = {}
    for p in original_png_files:
        try:
            with Image.open(p) as raw_img:
                raw_img.load()
                images[os.path.basename(p)] = raw_img.convert('RGBA')
        except Exception as e:
            for opened_img in images.values():
                opened_img.close()
            return f"Could not decode image file '{os.path.basename(p)}': {e}"

    merging_plan = {i: [] for i in range(target_count)}
    for i, png_path in enumerate(original_png_files):
        merging_plan[i % target_count].append(os.path.basename(png_path))

    if target_texture_names and len(target_texture_names) >= target_count:
        output_names = [f"{name}.png" for name in target_texture_names[:target_count]]
    else:
        output_names = [
            f"{base_name}.png" if i == 0 else f"{base_name}_{i + 1}.png"
            for i in range(target_count)
        ]

    final_atlas_blocks = []
    resample_filter = getattr(getattr(Image, 'Resampling', Image), 'LANCZOS', Image.LANCZOS)

    for i in range(target_count):
        target_pngs = merging_plan[i]
        if not target_pngs:
            continue

        group_imgs = [(name, images[name]) for name in target_pngs]
        canvas_w, canvas_h, merged_offsets = _pack_shelf_2d(group_imgs, MAX_ATLAS_PAGE_DIM)

        canvas = Image.new('RGBA', (canvas_w, canvas_h))
        for name, img in group_imgs:
            canvas.paste(img, merged_offsets[name])

        scale_x = 1.0
        scale_y = 1.0
        if canvas_w > MAX_ATLAS_PAGE_DIM or canvas_h > MAX_ATLAS_PAGE_DIM:
            scale = min(MAX_ATLAS_PAGE_DIM / float(canvas_w), MAX_ATLAS_PAGE_DIM / float(canvas_h))
            scaled_w = max(1, int(round(canvas_w * scale)))
            scaled_h = max(1, int(round(canvas_h * scale)))
            scale_x = scaled_w / float(canvas_w)
            scale_y = scaled_h / float(canvas_h)
            resized = canvas.resize((scaled_w, scaled_h), resample=resample_filter)
            canvas.close()
            canvas = resized
            canvas_w, canvas_h = scaled_w, scaled_h

        output_image_name = output_names[i]
        output_path = os.path.join(mod_dir_path, output_image_name)
        canvas.save(output_path)
        canvas.close()
        report_progress(f"Saved merged image: {output_path} ({canvas_w}x{canvas_h})")

        all_sprites_for_block = []
        header_lines = None
        for original_png_name in target_pngs:
            atlas_data = _find_atlas_entry(atlas_db, original_png_name)
            if not atlas_data:
                report_progress(f"WARNING: Missing atlas entry for {original_png_name}; skipping atlas block data during merge.")
                continue

            if header_lines is None:
                header_lines = atlas_data.get('header_lines') or [atlas_data['filter_line']]

            offset_x, offset_y = merged_offsets[original_png_name]
            transformed = offset_and_scale_atlas_sprites(
                atlas_data['sprites'],
                offset_x=offset_x,
                offset_y=offset_y,
                scale_x=scale_x,
                scale_y=scale_y,
            )
            if transformed:
                all_sprites_for_block.append(transformed)

        if header_lines is None:
            for opened_img in images.values():
                opened_img.close()
            return f"No atlas blocks matched merged texture group {target_pngs}."

        new_block = [
            output_image_name,
            f" size: {canvas_w},{canvas_h}",
            *header_lines,
            '\n'.join(all_sprites_for_block),
        ]
        final_atlas_blocks.append('\n'.join(new_block))

    with open(original_atlas_path, 'w', encoding='utf-8') as f:
        f.write('\n\n'.join(final_atlas_blocks))
    report_progress(f"Wrote final atlas file to: {original_atlas_path}")

    for img in images.values():
        img.close()
    del images

    output_paths = [os.path.join(mod_dir_path, n) for n in output_names]
    report_progress("Cleaning up original, unmerged texture files...")
    for png_path in original_png_files:
        try:
            if not os.path.exists(png_path):
                continue
            if not any(os.path.exists(op) and os.path.samefile(png_path, op) for op in output_paths):
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


def _compress_texture_worker(args):
    """
    Worker 函數，用於在子進程中執行 ASTC 壓縮。
    
    Args:
        args: tuple of (mod_filepath, target_asset_name, block_x, block_y)
    
    Returns:
        dict with keys: 'success', 'target_asset_name', 'mod_filepath', 
                       'compressed_data', 'width', 'height', 'error'
    """
    mod_filepath, target_asset_name, block_x, block_y = args
    result = {
        'success': False,
        'target_asset_name': target_asset_name,
        'mod_filepath': mod_filepath,
        'compressed_data': None,
        'width': 0,
        'height': 0,
        'error': None
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


def _asset_objects(asset_map, target_asset_name: str, type_name: str = None):
    objects = list(asset_map.get(target_asset_name, []))
    if type_name:
        objects = [obj for obj in objects if obj.type.name == type_name]
    return objects


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
        _ensure_temp_dir(working_dir, output_path)

        report_progress("Loading original game file...")
        env = UnityPy.load(original_bundle_path)
        edited = False

        # --- 建立檔案索引，一次遍歷取代多次 os.walk ---
        report_progress("Building file index...")
        file_index = _build_file_index(working_dir)

        report_progress("Scanning for moddable assets...")
        asset_map = {}
        texture_original_names = {}
        for obj in env.objects:
            if obj.type.name not in ("Texture2D", "TextAsset"):
                continue
            try:
                name = obj.peek_name()
                if name is None:
                    data = obj.read()
                    name = getattr(data, 'm_Name', None)
                if name:
                    key = name.lower()
                    asset_map.setdefault(key, []).append(obj)
                    if obj.type.name == "Texture2D" and key not in texture_original_names:
                        texture_original_names[key] = name
            except Exception:
                pass  # Skip objects that can't be read

        # 從索引中取得 spine mods
        spine_mods_to_process = {
            base_name: directory
            for base_name, (_file_type, _filepath, directory) in file_index['skel_json'].items()
        }

        if spine_mods_to_process:
            report_progress(f"Detected {len(spine_mods_to_process)} unique Spine mods for pre-processing: {list(spine_mods_to_process.keys())}")
            for spine_base_name, mod_dir_path in spine_mods_to_process.items():
                report_progress(f"--- Processing: {spine_base_name} ---")
                pattern = re.compile(rf"^{re.escape(spine_base_name)}(_\d+)?$", re.IGNORECASE)

                matching_tex_keys = [
                    key for key in texture_original_names
                    if pattern.match(key)
                ]

                def _tex_sort_key(k):
                    if k == spine_base_name.lower():
                        return (0, 0)
                    m = re.search(r'_(\d+)$', k)
                    return (1, int(m.group(1))) if m else (2, float('inf'))

                matching_tex_keys.sort(key=_tex_sort_key)
                target_texture_names = [texture_original_names[k] for k in matching_tex_keys]
                original_texture_count = len(target_texture_names)

                report_progress(f"Found {original_texture_count} matching textures in the original game file for {spine_base_name}.")

                _mod_png_pattern = re.compile(rf'^{re.escape(spine_base_name)}(_\d+)?\.png$', re.IGNORECASE)
                mod_texture_count = sum(
                    1 for f in os.listdir(mod_dir_path)
                    if os.path.isfile(os.path.join(mod_dir_path, f)) and _mod_png_pattern.match(f)
                )
                report_progress(f"Mod has {mod_texture_count} textures for {spine_base_name}.")

                if mod_texture_count > original_texture_count and original_texture_count > 0:
                    report_progress("Mod texture count exceeds original, starting merge process into temp directory...")
                    merge_error = _merge_spine_assets(
                        mod_dir_path,
                        spine_base_name,
                        original_texture_count,
                        report_progress,
                        target_texture_names=target_texture_names,
                    )

                    if merge_error:
                        err_msg = f"Spine merge failed for {spine_base_name}: {merge_error}"
                        report_progress(f"ERROR: {err_msg}")
                        return False, err_msg, []
                    report_progress(f"Merge successful for {spine_base_name}.")
                    # 合併後需要重新建立索引以包含新檔案
                    file_index = _build_file_index(working_dir)
                else:
                    report_progress("Texture count matches or is lower, no merge needed.")

        # 直接使用索引中的檔案列表
        mod_files = file_index['all_files']

        # ========== 階段一：分類所有 mod 檔案 ==========
        report_progress("Phase 1: Categorizing mod files...")

        json_files = []      # JSON -> SKEL 轉換
        png_astc_files = []  # PNG -> ASTC 壓縮 (需平行處理)
        png_rgba_files = []  # PNG -> RGBA32 (不需壓縮)
        text_files = []      # TextAsset 替換
        unmatched_mod_files = []  # 在此 bundle 中找不到對應資產的檔案

        for mod_filepath in mod_files:
            mod_filename = os.path.basename(mod_filepath)
            lower_name = mod_filename.lower()

            if lower_name.endswith('.json'):
                base_name, _ = os.path.splitext(mod_filename)
                target_asset_name = (base_name + ".skel").lower()
                if _asset_objects(asset_map, target_asset_name, "TextAsset"):
                    json_files.append((mod_filepath, target_asset_name))
                else:
                    unmatched_mod_files.append(mod_filename)

            elif lower_name.endswith('.png'):
                target_asset_name = os.path.splitext(mod_filename)[0].lower()
                if _asset_objects(asset_map, target_asset_name, "Texture2D"):
                    if use_astc:
                        png_astc_files.append((mod_filepath, target_asset_name))
                    else:
                        png_rgba_files.append((mod_filepath, target_asset_name))
                else:
                    unmatched_mod_files.append(mod_filename)
            else:
                candidate_names = [lower_name]
                if lower_name.endswith(('.bytes', '.txt')):
                    stripped = lower_name.rsplit('.', 1)[0]
                    if stripped:
                        candidate_names.append(stripped)
                matched_target = None
                for cand in candidate_names:
                    if _asset_objects(asset_map, cand, "TextAsset"):
                        matched_target = cand
                        break
                if matched_target:
                    text_files.append((mod_filepath, matched_target))
                else:
                    unmatched_mod_files.append(mod_filename)

        if unmatched_mod_files:
            report_progress("WARNING: {} file(s) did not match any asset in this bundle: {}".format(
                len(unmatched_mod_files), ', '.join(sorted(unmatched_mod_files)[:8])))

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
                target_objects = _asset_objects(asset_map, target_asset_name, "TextAsset")
                skel_binary_data = json_to_skel_bytes(mod_filepath)

                for obj in target_objects:
                    data = obj.read()
                    data.m_Script = skel_binary_data.decode("utf-8", "surrogateescape")
                    data.save()
                    edited = True
                report_progress(f"{current_progress}Successfully replaced: {mod_filename} -> {len(target_objects)} object(s)")

            except Exception:
                import traceback
                report_progress(f"Error processing {mod_filename}: {traceback.format_exc()}")
        
        # 處理 TextAsset
        for i, (mod_filepath, target_asset_name) in enumerate(text_files):
            mod_filename = os.path.basename(mod_filepath)
            current_progress = f"(Text {i+1}/{len(text_files)}) "
            
            try:
                report_progress(f"{current_progress}Replacing asset: {mod_filename}")
                target_objects = _asset_objects(asset_map, target_asset_name, "TextAsset")
                with open(mod_filepath, "rb") as f:
                    new_script = f.read().decode("utf-8", "surrogateescape")

                for obj in target_objects:
                    data = obj.read()
                    data.m_Script = new_script
                    data.save()
                    edited = True
                
            except Exception as e:
                import traceback
                report_progress(f"Error processing {mod_filename}: {traceback.format_exc()}")
        
        # ========== 階段三：平行處理 ASTC 紋理壓縮 ==========
        # 優化策略：單一 Executor + 完成一個就立即寫入，控制記憶體峰值
        if png_astc_files:
            executor_type = "Thread" if IS_ANDROID else "Process"
            total_textures = len(png_astc_files)
            
            report_progress(f"Phase 3: Parallel ASTC compression ({total_textures} textures, {MAX_PARALLEL_TEXTURES} {executor_type} workers)...")
            
            block_x, block_y = 4, 4
            total_success = 0
            total_failed = 0
            completed_count = 0
            
            # 準備所有 worker 參數
            worker_args = [
                (mod_filepath, target_asset_name, block_x, block_y)
                for mod_filepath, target_asset_name in png_astc_files
            ]
            
            # 根據環境選擇 Executor
            ExecutorClass = ThreadPoolExecutor if IS_ANDROID else ProcessPoolExecutor
            
            try:
                with ExecutorClass(max_workers=MAX_PARALLEL_TEXTURES) as executor:
                    # 一次提交所有任務
                    future_to_args = {
                        executor.submit(_compress_texture_worker, args): args 
                        for args in worker_args
                    }
                    
                    # 完成一個就立即處理（寫入 + 清理）
                    for future in as_completed(future_to_args):
                        completed_count += 1
                        args = future_to_args[future]
                        mod_filename = os.path.basename(args[0])
                        
                        try:
                            result = future.result()
                            
                            if result['success']:
                                # 立即寫入 Bundle
                                target_asset_name = result['target_asset_name']
                                try:
                                    target_objects = _asset_objects(asset_map, target_asset_name, "Texture2D")
                                    for obj in target_objects:
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
                                    total_success += len(target_objects)
                                    
                                except Exception as e:
                                    total_failed += 1
                                    report_progress(f"  Write error {mod_filename}: {e}")
                            else:
                                total_failed += 1
                                report_progress(f"  FAILED: {mod_filename} - {result['error']}")
                            
                            # 立即清理這個結果的記憶體
                            del result
                                
                        except Exception as e:
                            total_failed += 1
                            report_progress(f"  ERROR: {mod_filename} - {str(e)}")
                        
                        # 定期報告進度（每 25% 或每 10 個）
                        if total_textures <= 10 or completed_count == total_textures or completed_count % max(1, total_textures // 4) == 0:
                            report_progress(f"  Progress: {completed_count}/{total_textures} ({100*completed_count//total_textures}%)")
                        
                        # 定期執行 gc（每處理 10 個紋理）
                        if completed_count % 10 == 0:
                            gc.collect()
                            
            except Exception as e:
                report_progress(f"Executor failed, falling back to sequential: {e}")
                # 回退到序列處理
                for args in worker_args:
                    completed_count += 1
                    result = _compress_texture_worker(args)
                    
                    if result['success']:
                        target_asset_name = result['target_asset_name']
                        mod_filename = os.path.basename(result['mod_filepath'])
                        try:
                            target_objects = _asset_objects(asset_map, target_asset_name, "Texture2D")
                            for obj in target_objects:
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
                            total_success += len(target_objects)
                        except Exception as e:
                            total_failed += 1
                    else:
                        total_failed += 1
                        mod_filename = os.path.basename(args[0])
                        report_progress(f"  FAILED (seq): {mod_filename} - {result['error']}")
                    
                    del result
                    if completed_count % 10 == 0:
                        gc.collect()
            
            # 最終清理
            del worker_args
            gc.collect()
            
            # 最終報告
            report_progress(f"  ASTC compression complete: {total_success} success, {total_failed} failed")
        
        # ========== 處理 RGBA32 紋理 (不需壓縮，序列處理) ==========
        if png_rgba_files:
            report_progress(f"Processing RGBA32 textures ({len(png_rgba_files)})...")
            
            for i, (mod_filepath, target_asset_name) in enumerate(png_rgba_files):
                mod_filename = os.path.basename(mod_filepath)
                current_progress = f"(RGBA {i+1}/{len(png_rgba_files)}) "
                
                pil_img = None
                try:
                    report_progress(f"{current_progress}Processing: {mod_filename}")
                    target_objects = _asset_objects(asset_map, target_asset_name, "Texture2D")
                    pil_img = Image.open(mod_filepath).convert("RGBA")

                    for obj in target_objects:
                        data = obj.read()
                        
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
                return True, "Repack completed successfully.", unmatched_mod_files
            except Exception as e:
                error_msg = f"Error saving bundle: {e}"
                report_progress(error_msg)
                return False, error_msg, unmatched_mod_files
        else:
            error_msg = "No modifications were made. Check if your mod files match any assets in the bundle."
            report_progress(error_msg)
            return False, error_msg, unmatched_mod_files

    except Exception as e:
        import traceback
        error_message = traceback.format_exc()
        report_progress(f"Error processing bundle: {error_message}")
        return False, error_message, []
    finally:
        if env is not None:
            del env
        gc.collect()
