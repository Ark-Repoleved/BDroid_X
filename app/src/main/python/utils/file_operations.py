"""File operation utilities for BDroid_X."""
import os
from typing import Optional


def find_file_case_insensitive(folder: str, filename: str) -> Optional[str]:
    """
    Search for a file in a folder with case-insensitive matching.
    
    Args:
        folder: Directory to search in
        filename: Name of the file to find
        
    Returns:
        Full path to the file if found, None otherwise
    """
    filename_lower = filename.lower()
    for root, dirs, files in os.walk(folder):
        for f in files:
            if f.lower() == filename_lower:
                return os.path.join(root, f)
    return None


def safe_remove(path: str, silent: bool = True) -> bool:
    """
    Safely remove a file, catching exceptions.
    
    Args:
        path: Path to the file to remove
        silent: If True, suppress error messages
        
    Returns:
        True if file was removed successfully, False otherwise
    """
    try:
        os.remove(path)
        return True
    except OSError as e:
        if not silent:
            print(f"Could not remove {path}: {e}")
        return False


def get_files_by_extension(directory: str, extension: str, recursive: bool = False) -> list:
    """
    Get all files with a specific extension in a directory.
    
    Args:
        directory: Directory to search
        extension: File extension (with or without leading dot)
        recursive: Whether to search recursively
        
    Returns:
        List of file paths
    """
    if not extension.startswith('.'):
        extension = '.' + extension
    
    files = []
    if recursive:
        for root, _, filenames in os.walk(directory):
            for filename in filenames:
                if filename.lower().endswith(extension.lower()):
                    files.append(os.path.join(root, filename))
    else:
        for filename in os.listdir(directory):
            filepath = os.path.join(directory, filename)
            if os.path.isfile(filepath) and filename.lower().endswith(extension.lower()):
                files.append(filepath)
    
    return files
