package com.example.bd2modmanager.gpu

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES31
import android.util.Log

/**
 * Utility class to check GPU capabilities for ASTC compression.
 */
object GpuCapability {
    
    private const val TAG = "GpuCapability"
    
    private var cachedSupport: Boolean? = null
    private var cachedMaxWorkGroupSize: IntArray? = null
    
    /**
     * Check if the device supports OpenGL ES 3.1 compute shaders.
     * Results are cached after first call.
     */
    fun isComputeShaderSupported(context: Context): Boolean {
        cachedSupport?.let { return it }
        
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val configInfo = activityManager?.deviceConfigurationInfo
        
        // Check for OpenGL ES 3.1+ support
        val reqGlVersion = configInfo?.reqGlEsVersion ?: 0
        val majorVersion = (reqGlVersion and 0xFFFF0000.toInt()) shr 16
        val minorVersion = reqGlVersion and 0x0000FFFF
        
        val supported = if (majorVersion > 3) {
            true
        } else if (majorVersion == 3) {
            minorVersion >= 1
        } else {
            false
        }
        
        Log.i(TAG, "OpenGL ES version: $majorVersion.$minorVersion, compute shader supported: $supported")
        cachedSupport = supported
        return supported
    }
    
    /**
     * Get maximum compute work group sizes.
     * Returns [maxX, maxY, maxZ] or null if not supported.
     */
    fun getMaxWorkGroupSize(): IntArray? {
        cachedMaxWorkGroupSize?.let { return it }
        
        // This requires a valid GL context, so we'll do a quick check
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            return null
        }
        
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            return null
        }
        
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x00000040, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            EGL14.eglTerminate(display)
            return null
        }
        
        if (numConfigs[0] == 0) {
            EGL14.eglTerminate(display)
            return null
        }
        
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        
        val context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        
        if (context == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(display)
            return null
        }
        
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
        
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return null
        }
        
        val maxWorkGroupSize = IntArray(3)
        val result = IntArray(1)
        
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0, result, 0)
        maxWorkGroupSize[0] = result[0]
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 1, result, 0)
        maxWorkGroupSize[1] = result[0]
        GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, 2, result, 0)
        maxWorkGroupSize[2] = result[0]
        
        Log.i(TAG, "Max compute work group size: ${maxWorkGroupSize[0]}x${maxWorkGroupSize[1]}x${maxWorkGroupSize[2]}")
        
        // Cleanup
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        
        cachedMaxWorkGroupSize = maxWorkGroupSize
        return maxWorkGroupSize
    }
    
    /**
     * Reset cached values (useful for testing).
     */
    fun resetCache() {
        cachedSupport = null
        cachedMaxWorkGroupSize = null
    }
}
