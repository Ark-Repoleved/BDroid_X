"""Atlas file parsing and transformation utilities for BDroid_X."""
import re
from typing import Dict, List, Optional, Tuple


_PAGE_HEADER_PREFIXES = (
    'size:',
    'format:',
    'filter:',
    'repeat:',
    'pma:',
    'scale:',
)


def parse_atlas_file(atlas_path: str) -> Tuple[Optional[Dict], Optional[str]]:
    """
    Parse a .atlas file into a structured dictionary.

    Returns:
        Tuple of (atlas_database dict, error message).
        Each entry contains:
            - 'size_line': optional str
            - 'filter_line': str
            - 'header_lines': list[str] (format/filter/repeat/pma/scale in order)
            - 'sprites': str
    """
    atlas_database = {}

    try:
        with open(atlas_path, 'r', encoding='utf-8-sig') as f:
            content = f.read()
    except FileNotFoundError:
        return None, f"Atlas file not found at {atlas_path}"
    except Exception as e:
        return None, f"Error reading atlas file: {e}"

    normalized = content.replace('\r\n', '\n').replace('\r', '\n').strip()
    if not normalized:
        return None, f"Atlas file is empty: {atlas_path}"

    blocks = re.split(r'\n+(?=[^\n]+\.(?:png|jpg|jpeg|webp)\n)', normalized, flags=re.IGNORECASE)

    for block_text in blocks:
        block_text = block_text.strip()
        if not block_text:
            continue

        lines = block_text.split('\n')
        block_name = lines[0].strip()

        data = {
            'size_line': '',
            'filter_line': ' filter: Linear, Linear',
            'header_lines': [],
            'sprites': '',
        }
        sprite_lines: List[str] = []
        in_page_header = True

        for line in lines[1:]:
            stripped_line = line.strip()
            lowered = stripped_line.lower()
            if in_page_header and any(lowered.startswith(prefix) for prefix in _PAGE_HEADER_PREFIXES):
                if lowered.startswith('size:'):
                    data['size_line'] = line
                elif lowered.startswith('filter:'):
                    data['filter_line'] = line
                    data['header_lines'].append(line)
                else:
                    data['header_lines'].append(line)
            else:
                if stripped_line:
                    in_page_header = False
                sprite_lines.append(line)

        if not any(h.strip().lower().startswith('filter:') for h in data['header_lines']):
            data['header_lines'].insert(0, data['filter_line'])

        data['sprites'] = '\n'.join(sprite_lines)
        atlas_database[block_name] = data

    return atlas_database, None


def offset_and_scale_atlas_sprites(
    sprites_text: str,
    offset_x: int = 0,
    offset_y: int = 0,
    scale_x: float = 1.0,
    scale_y: float = 1.0,
) -> str:
    """
    Transform region coordinates inside a Spine .atlas sprites block.
    Supports both Spine 4.1+ (`bounds: x,y,w,h` + `offsets:`) and
    Spine 3.8/4.0 (`xy: x, y` + `size: w, h` + `orig:` + `offset:`).
    """
    if offset_x == 0 and offset_y == 0 and scale_x == 1.0 and scale_y == 1.0:
        return sprites_text

    out_lines: List[str] = []
    for line in sprites_text.split('\n'):
        stripped = line.strip()
        indent = line[:len(line) - len(line.lstrip())]

        if ':' not in stripped:
            out_lines.append(line)
            continue

        key, raw_val = stripped.split(':', 1)
        key_lower = key.strip().lower()
        parts = [p.strip() for p in raw_val.split(',')]

        try:
            if key_lower == 'bounds' and len(parts) == 4:
                x, y, w, h = [int(p) for p in parts]
                nx = int(round((x + offset_x) * scale_x))
                ny = int(round((y + offset_y) * scale_y))
                nw = max(1, int(round(w * scale_x))) if w > 0 else 0
                nh = max(1, int(round(h * scale_y))) if h > 0 else 0
                out_lines.append(f"{indent}bounds: {nx},{ny},{nw},{nh}")
                continue

            if key_lower == 'xy' and len(parts) == 2:
                x, y = [int(p) for p in parts]
                nx = int(round((x + offset_x) * scale_x))
                ny = int(round((y + offset_y) * scale_y))
                out_lines.append(f"{indent}xy: {nx}, {ny}")
                continue

            if key_lower == 'size' and len(parts) == 2 and (scale_x != 1.0 or scale_y != 1.0):
                w, h = [int(p) for p in parts]
                nw = max(1, int(round(w * scale_x))) if w > 0 else 0
                nh = max(1, int(round(h * scale_y))) if h > 0 else 0
                out_lines.append(f"{indent}size: {nw}, {nh}")
                continue

            if key_lower == 'offsets' and len(parts) == 4 and (scale_x != 1.0 or scale_y != 1.0):
                ox, oy, ow, oh = [int(p) for p in parts]
                nox = int(round(ox * scale_x))
                noy = int(round(oy * scale_y))
                now = max(1, int(round(ow * scale_x))) if ow > 0 else 0
                noh = max(1, int(round(oh * scale_y))) if oh > 0 else 0
                out_lines.append(f"{indent}offsets: {nox},{noy},{now},{noh}")
                continue

            if key_lower == 'orig' and len(parts) == 2 and (scale_x != 1.0 or scale_y != 1.0):
                ow, oh = [int(p) for p in parts]
                now = max(1, int(round(ow * scale_x))) if ow > 0 else 0
                noh = max(1, int(round(oh * scale_y))) if oh > 0 else 0
                out_lines.append(f"{indent}orig: {now}, {noh}")
                continue

            if key_lower == 'offset' and len(parts) == 2 and (scale_x != 1.0 or scale_y != 1.0):
                ox, oy = [int(p) for p in parts]
                nox = int(round(ox * scale_x))
                noy = int(round(oy * scale_y))
                out_lines.append(f"{indent}offset: {nox}, {noy}")
                continue
        except (ValueError, TypeError):
            pass

        out_lines.append(line)

    return '\n'.join(out_lines)
