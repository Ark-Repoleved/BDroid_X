
import ctypes
import os

# Load the shared library
try:
    # Chaquopy will place the .so in the lib directory
    lib = ctypes.cdll.LoadLibrary("libtexture2ddecoder.so")
except OSError as e:
    raise ImportError(f"Failed to load libtexture2ddecoder.so. Make sure it's in jniLibs. Error: {e}")

# Helper function to handle the common decode pattern
def _decode_image(func_name, data, width, height, *extra_args):
    image_buffer = (ctypes.c_uint * (width * height))()
    
    c_func = getattr(lib, func_name)
    c_func.argtypes = [
        ctypes.c_void_p, 
        ctypes.c_long, 
        ctypes.c_long, 
        ctypes.c_void_p
    ] + [type(arg) for arg in extra_args]
    c_func.restype = ctypes.c_int

    data_ptr = ctypes.cast(data, ctypes.c_void_p)
    image_ptr = ctypes.cast(image_buffer, ctypes.c_void_p)

    ret = c_func(data_ptr, width, height, image_ptr, *extra_args)
    if ret != 0:
        # Most decoders don't have specific error messages, so we return a generic one
        raise Exception(f"{func_name} failed with return code {ret}")

    return bytes(image_buffer)

# Helper for crunch functions
def _unpack_crunch(func_name, data):
    c_func = getattr(lib, func_name)
    c_func.argtypes = [
        ctypes.c_void_p,
        ctypes.c_uint,
        ctypes.c_uint,
        ctypes.POINTER(ctypes.c_void_p),
        ctypes.POINTER(ctypes.c_uint)
    ]
    c_func.restype = ctypes.c_bool

    data_ptr = ctypes.cast(data, ctypes.c_void_p)
    data_size = len(data)
    
    ret_ptr = ctypes.c_void_p()
    ret_size = ctypes.c_uint()

    success = c_func(data_ptr, data_size, 0, ctypes.byref(ret_ptr), ctypes.byref(ret_size))

    if not success:
        raise Exception(f"{func_name} failed.")

    unpacked_data = ctypes.string_at(ret_ptr, ret_size.value)
    # We have to assume the C code doesn't leak, as there's no free function exposed.
    return unpacked_data

# Public API functions that UnityPy expects
def unpack_crunch(data):
    return _unpack_crunch("crunch_unpack_level", data)

def unpack_unity_crunch(data):
    return _unpack_crunch("unity_crunch_unpack_level", data)

def decode_atc_rgb4(data, width, height):
    return _decode_image("decode_atc_rgb4", data, width, height)

def decode_atc_rgba8(data, width, height):
    return _decode_image("decode_atc_rgba8", data, width, height)

def decode_pvrtc(data, width, height, fmt):
    return _decode_image("decode_pvrtc", data, width, height, fmt)

def decode_etc1(data, width, height):
    return _decode_image("decode_etc1", data, width, height)

def decode_etc2(data, width, height):
    return _decode_image("decode_etc2", data, width, height)

def decode_etc2a1(data, width, height):
    return _decode_image("decode_etc2a1", data, width, height)

def decode_etc2a8(data, width, height):
    return _decode_image("decode_etc2a8", data, width, height)

def decode_eacr(data, width, height):
    return _decode_image("decode_eacr", data, width, height)

def decode_eacr_signed(data, width, height):
    return _decode_image("decode_eacr_signed", data, width, height)

def decode_eacrg(data, width, height):
    return _decode_image("decode_eacrg", data, width, height)

def decode_eacrg_signed(data, width, height):
    return _decode_image("decode_eacrg_signed", data, width, height)

def decode_bc1(data, width, height):
    return _decode_image("decode_bc1", data, width, height)

def decode_bc3(data, width, height):
    return _decode_image("decode_bc3", data, width, height)

def decode_bc4(data, width, height):
    return _decode_image("decode_bc4", data, width, height)

def decode_bc5(data, width, height):
    return _decode_image("decode_bc5", data, width, height)

def decode_bc6(data, width, height):
    return _decode_image("decode_bc6", data, width, height)

def decode_bc7(data, width, height):
    return _decode_image("decode_bc7", data, width, height)

