"""Atlas file parsing utilities for BDroid_X."""
import re
from typing import Dict, Optional, Tuple


def parse_atlas_file(atlas_path: str) -> Tuple[Optional[Dict], Optional[str]]:
    """
    Parse a .atlas file into a structured dictionary.
    
    Args:
        atlas_path: Path to the .atlas file
        
    Returns:
        Tuple of (atlas_database dict, error message)
        If successful, returns (dict, None)
        If failed, returns (None, error_message)
    """
    atlas_database = {}
    
    try:
        with open(atlas_path, 'r', encoding='utf-8') as f:
            content = f.read()
    except FileNotFoundError:
        return None, f"Atlas file not found at {atlas_path}"
    except Exception as e:
        return None, f"Error reading atlas file: {e}"

    # Split content into blocks (one per PNG image)
    blocks = re.split(r'\n(?=[^\n]+\.png\n)', content.strip())
    
    for block_text in blocks:
        block_text = block_text.strip()
        if not block_text:
            continue
        
        lines = block_text.split('\n')
        block_name = lines[0]
        
        data = {'sprites': [], 'filter_line': ' filter: Linear, Linear'}
        sprite_lines = []
        
        for line in lines[1:]:
            stripped_line = line.strip()
            if stripped_line.startswith('size:'):
                data['size_line'] = line
            elif stripped_line.startswith('filter:'):
                data['filter_line'] = line
            else:
                sprite_lines.append(line)

        data['sprites'] = '\n'.join(sprite_lines)
        atlas_database[block_name] = data
    
    return atlas_database, None
