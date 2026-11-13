import glob
import os
import re
import shutil
from PIL import Image, ImageFile

from utils.atlas_operations import parse_atlas_file, offset_and_scale_atlas_sprites

ImageFile.LOAD_TRUNCATED_IMAGES = True


def _generate_operations(base_dir, file_prefix, progress_callback):
    """Automatically scans a directory and generates merge/copy operations."""
    progress_callback(f"  Scanning '{base_dir}' for files with prefix '{file_prefix}'...")

    pattern = re.compile(rf'^{re.escape(file_prefix)}(_\d+)?\.png$', re.IGNORECASE)
    png_files = [
        f for f in os.listdir(base_dir)
        if os.path.isfile(os.path.join(base_dir, f)) and pattern.match(f)
    ]

    def sort_key(filename):
        if filename.lower() == f"{file_prefix.lower()}.png":
            return 1
        match = re.search(r'_(\d+)\.png$', filename, re.IGNORECASE)
        if match:
            return int(match.group(1))
        return float('inf')

    png_files.sort(key=sort_key)
    progress_callback(f"  Found and sorted {len(png_files)} image files: {png_files}")

    operations = []
    source_pngs_for_backup = png_files[:]
    i = 0
    pair_index = 1
    while i < len(png_files):
        if pair_index == 1:
            output_filename = f"{file_prefix}.png"
        else:
            output_filename = f"{file_prefix}_{pair_index}.png"

        if i + 1 < len(png_files):
            op = {"type": "merge", "sources": [png_files[i], png_files[i + 1]], "output": output_filename}
            operations.append(op)
            i += 2
        else:
            op = {"type": "copy", "sources": [png_files[i]], "output": output_filename}
            operations.append(op)
            i += 1
        pair_index += 1

    progress_callback("  Generated the following operations:")
    for op in operations:
        progress_callback(f"    - {op['type']}: {op['sources']} -> {op['output']}")
    return operations, source_pngs_for_backup


def _find_atlas_entry(atlas_db, png_name):
    if png_name in atlas_db:
        return atlas_db[png_name]
    lowered = png_name.lower()
    for key, val in atlas_db.items():
        if key.lower() == lowered:
            return val
    return None


def run(mod_dir_path, progress_callback=print):
    """Processes all assets for a single mod directory in-place."""
    progress_callback(f"\n--- Processing Mod Directory: {mod_dir_path} ---")

    atlas_files = glob.glob(os.path.join(mod_dir_path, '*.atlas'))
    if not atlas_files:
        atlas_files = glob.glob(os.path.join(mod_dir_path, '*.atlas.txt'))
    if not atlas_files:
        return "SKIPPING: No .atlas file found."

    original_atlas_path = atlas_files[0]
    atlas_basename = os.path.basename(original_atlas_path)
    if atlas_basename.lower().endswith('.atlas.txt'):
        file_prefix = atlas_basename[:-10]
    else:
        file_prefix = os.path.splitext(atlas_basename)[0]

    # 1. Generate operations and get a list of all pngs to be processed
    operations, all_source_pngs = _generate_operations(mod_dir_path, file_prefix, progress_callback)
    if not operations:
        return "SKIPPING: No image operations to perform."

    # 2. Parse original Atlas
    atlas_db, err = parse_atlas_file(original_atlas_path)
    if err or not atlas_db:
        return f"FAILED: Could not parse atlas file: {err or 'empty atlas'}"

    # Pre-validate all source images before moving anything to .old
    for png_file in all_source_pngs:
        src_path = os.path.join(mod_dir_path, png_file)
        try:
            with Image.open(src_path) as img:
                img.load()
        except Exception as e:
            return f"FAILED: Could not decode image '{png_file}': {e}"

    # 3. Create .old directory and move original files
    old_dir = os.path.join(mod_dir_path, ".old")
    os.makedirs(old_dir, exist_ok=True)
    progress_callback(f"  Created backup directory: {old_dir}")

    shutil.move(original_atlas_path, os.path.join(old_dir, atlas_basename))
    for png_file in all_source_pngs:
        shutil.move(os.path.join(mod_dir_path, png_file), os.path.join(old_dir, png_file))
    progress_callback(f"  Moved original atlas and {len(all_source_pngs)} PNG files to .old directory.")

    final_atlas_blocks = []

    # 4. Process all image operations
    for op in operations:
        op_type = op['type']
        sources = op['sources']
        output_name = op['output']
        output_path = os.path.join(mod_dir_path, output_name)

        if op_type == 'copy':
            source_path = os.path.join(old_dir, sources[0])
            shutil.copy(source_path, output_path)
            original_data = _find_atlas_entry(atlas_db, sources[0])
            if original_data:
                new_block = [output_name]
                if original_data.get('size_line'):
                    new_block.append(original_data['size_line'])
                new_block.extend(original_data.get('header_lines') or [original_data['filter_line']])
                new_block.append(original_data['sprites'])
                final_atlas_blocks.append('\n'.join(new_block))

        elif op_type == 'merge':
            img1_name, img2_name = sources[0], sources[1]
            img1_path = os.path.join(old_dir, img1_name)
            img2_path = os.path.join(old_dir, img2_name)
            with Image.open(img1_path) as raw1, Image.open(img2_path) as raw2:
                img1 = raw1.convert('RGBA')
                img2 = raw2.convert('RGBA')
                w1, h1 = img1.size
                w2, h2 = img2.size

                # Place side by side unless total width exceeds 4096px; otherwise stack vertically
                if w1 + w2 <= 4096:
                    new_width = w1 + w2
                    new_height = max(h1, h2)
                    offset_x, offset_y = w1, 0
                else:
                    new_width = max(w1, w2)
                    new_height = h1 + h2
                    offset_x, offset_y = 0, h1

                new_img = Image.new('RGBA', (new_width, new_height))
                new_img.paste(img1, (0, 0))
                new_img.paste(img2, (offset_x, offset_y))
                new_img.save(output_path)
                new_img.close()
                img1.close()
                img2.close()

            img1_data = _find_atlas_entry(atlas_db, img1_name)
            img2_data = _find_atlas_entry(atlas_db, img2_name)
            if not img1_data or not img2_data:
                continue

            modified_sprites_img2 = offset_and_scale_atlas_sprites(
                img2_data['sprites'], offset_x=offset_x, offset_y=offset_y
            )
            header_lines = img1_data.get('header_lines') or [img1_data['filter_line']]
            new_block_content = [
                output_name,
                f" size: {new_width},{new_height}",
                *header_lines,
                img1_data['sprites'],
                modified_sprites_img2,
            ]
            final_atlas_blocks.append('\n'.join(new_block_content))

    # 5. Write final Atlas file to the mod directory
    final_atlas_path = os.path.join(mod_dir_path, atlas_basename)
    progress_callback(f"  Writing final atlas file to: {final_atlas_path}")
    with open(final_atlas_path, 'w', encoding='utf-8') as f:
        f.write('\n\n'.join(final_atlas_blocks))

    return f"Successfully merged files in {mod_dir_path}"
