"""
GPU ASTC Compression Bridge for Python/Chaquopy

This module provides a Python interface to the GPU-accelerated ASTC compression
implemented in Kotlin. It automatically falls back to CPU compression if GPU
is not available.
"""

import os
import sys

# GPU compression state
_gpu_available = None
_bridge_class = None

def _get_bridge():
    """
    Get the Kotlin bridge class via Chaquopy's Java interface.
    Returns None if not available (e.g., not on Android, or bridge not initialized).
    """
    global _bridge_class
    
    if _bridge_class is not None:
        return _bridge_class
    
    # Only try on Android
    if not (hasattr(sys, 'getandroidapilevel') or 'ANDROID_ROOT' in os.environ):
        return None
    
    try:
        from java import jclass
        _bridge_class = jclass("com.example.bd2modmanager.gpu.AstcCompressorBridge")
        return _bridge_class
    except Exception as e:
        print(f"[GPU ASTC] Failed to load bridge class: {e}")
        return None


def is_gpu_available():
    """
    Check if GPU ASTC compression is available.
    
    Returns:
        bool: True if GPU compression is available, False otherwise
    """
    global _gpu_available
    
    if _gpu_available is not None:
        return _gpu_available
    
    bridge = _get_bridge()
    if bridge is None:
        _gpu_available = False
        return False
    
    try:
        _gpu_available = bridge.isGpuAvailable()
        print(f"[GPU ASTC] GPU available: {_gpu_available}")
        return _gpu_available
    except Exception as e:
        print(f"[GPU ASTC] Error checking GPU availability: {e}")
        _gpu_available = False
        return False


def compress_with_gpu(input_path, output_path, flip_y=True):
    """
    Compress an image to ASTC format using GPU.
    
    Args:
        input_path: Path to input PNG file
        output_path: Path to write compressed ASTC data
        flip_y: Whether to flip Y axis (default: True for Unity textures)
    
    Returns:
        bool: True if compression succeeded, False otherwise
    """
    bridge = _get_bridge()
    if bridge is None:
        return False
    
    try:
        return bridge.compressWithGpu(input_path, output_path, flip_y)
    except Exception as e:
        print(f"[GPU ASTC] Compression failed: {e}")
        return False


def compress_image_bytes_gpu(image_bytes, width, height, flip_y=True):
    """
    Compress raw RGBA image bytes to ASTC format using GPU.
    
    This is a more direct interface that avoids file I/O, but requires
    creating a temporary file on Android due to Chaquopy/Java limitations.
    
    Args:
        image_bytes: Raw RGBA image data
        width: Image width
        height: Image height
        flip_y: Whether to flip Y axis
    
    Returns:
        bytes: Compressed ASTC data, or None on failure
    """
    import tempfile
    
    bridge = _get_bridge()
    if bridge is None:
        return None
    
    # We need to write to a temp file and read back
    # because passing large byte arrays through Chaquopy is slow
    try:
        # Save image bytes to temp PNG
        from PIL import Image
        import io
        
        img = Image.frombytes('RGBA', (width, height), image_bytes)
        
        with tempfile.NamedTemporaryFile(suffix='.png', delete=False) as tmp_in:
            tmp_input_path = tmp_in.name
            img.save(tmp_input_path, 'PNG')
        
        with tempfile.NamedTemporaryFile(suffix='.astc', delete=False) as tmp_out:
            tmp_output_path = tmp_out.name
        
        try:
            success = bridge.compressWithGpu(tmp_input_path, tmp_output_path, flip_y)
            
            if success:
                with open(tmp_output_path, 'rb') as f:
                    return f.read()
            else:
                return None
                
        finally:
            # Cleanup temp files
            if os.path.exists(tmp_input_path):
                os.remove(tmp_input_path)
            if os.path.exists(tmp_output_path):
                os.remove(tmp_output_path)
                
    except Exception as e:
        print(f"[GPU ASTC] Bytes compression failed: {e}")
        return None


def release_gpu():
    """
    Release GPU resources.
    Should be called when done with GPU compression to free memory.
    """
    bridge = _get_bridge()
    if bridge is not None:
        try:
            bridge.release()
            print("[GPU ASTC] GPU resources released")
        except Exception as e:
            print(f"[GPU ASTC] Error releasing resources: {e}")
