package com.example.bd2modmanager.gpu

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GPU-accelerated ASTC 4x4 texture encoder using OpenGL ES 3.1 Compute Shaders.
 * 
 * This class handles:
 * - EGL context creation (offscreen, no display needed)
 * - Compute shader compilation
 * - Texture upload and compression
 * - Memory management
 */
class GpuAstcEncoder(private val shaderSource: String) : AutoCloseable {
    
    companion object {
        private const val TAG = "GpuAstcEncoder"
        private const val BLOCK_SIZE = 4
        private const val BYTES_PER_BLOCK = 16
    }
    
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var computeProgram: Int = 0
    private var isInitialized = false
    
    // Uniform locations
    private var uTexelWidthLoc: Int = -1
    private var uTexelHeightLoc: Int = -1
    private var uFlipYLoc: Int = -1
    
    /**
     * Initialize EGL context and compile compute shader.
     * Must be called before compress().
     * 
     * @return true if initialization succeeded, false otherwise
     */
    fun initialize(): Boolean {
        if (isInitialized) return true
        
        try {
            if (!initEGL()) {
                Log.e(TAG, "Failed to initialize EGL")
                return false
            }
            
            if (!compileShader()) {
                Log.e(TAG, "Failed to compile compute shader")
                releaseEGL()
                return false
            }
            
            isInitialized = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            release()
            return false
        }
    }
    
    /**
     * Compress a bitmap to ASTC 4x4 format.
     * 
     * @param bitmap Input bitmap (will be converted to RGBA if needed)
     * @param flipY Whether to flip the Y axis (for OpenGL/Unity coordinate system)
     * @return Compressed ASTC data, or null on failure
     */
    fun compress(bitmap: Bitmap, flipY: Boolean = true): ByteArray? {
        if (!isInitialized) {
            Log.e(TAG, "Encoder not initialized")
            return null
        }
        
        // Make context current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "Failed to make EGL context current")
            return null
        }
        
        val width = bitmap.width
        val height = bitmap.height
        val blocksX = (width + BLOCK_SIZE - 1) / BLOCK_SIZE
        val blocksY = (height + BLOCK_SIZE - 1) / BLOCK_SIZE
        val totalBlocks = blocksX * blocksY
        val outputSize = totalBlocks * BYTES_PER_BLOCK
        
        var inputTexture = 0
        var outputBuffer = 0
        
        try {
            // Create input texture
            val textures = IntArray(1)
            GLES31.glGenTextures(1, textures, 0)
            inputTexture = textures[0]
            
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, inputTexture)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
            
            // Upload bitmap to texture
            val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
            pixelBuffer.order(ByteOrder.nativeOrder())
            
            // Convert bitmap to RGBA bytes
            val rgbaBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            rgbaBitmap.copyPixelsToBuffer(pixelBuffer)
            pixelBuffer.position(0)
            
            // Note: Android Bitmap is ARGB, but we need RGBA
            // Swizzle will be handled by reading properly
            GLES31.glTexImage2D(
                GLES31.GL_TEXTURE_2D, 0, GLES31.GL_RGBA8,
                width, height, 0,
                GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE,
                pixelBuffer
            )
            
            if (rgbaBitmap !== bitmap) {
                rgbaBitmap.recycle()
            }
            
            checkGlError("Upload texture")
            
            // Create output SSBO
            val buffers = IntArray(1)
            GLES31.glGenBuffers(1, buffers, 0)
            outputBuffer = buffers[0]
            
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, outputBuffer)
            GLES31.glBufferData(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                outputSize.toLong(),
                null,
                GLES31.GL_DYNAMIC_READ
            )
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, outputBuffer)
            
            checkGlError("Create output buffer")
            
            // Use compute shader
            GLES31.glUseProgram(computeProgram)
            
            // Set uniforms
            GLES31.glUniform1i(uTexelWidthLoc, width)
            GLES31.glUniform1i(uTexelHeightLoc, height)
            GLES31.glUniform1i(uFlipYLoc, if (flipY) 1 else 0)
            
            // Bind input texture
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, inputTexture)
            
            checkGlError("Setup dispatch")
            
            // Dispatch compute shader
            // Each work group is 8x8, we need to cover all blocks
            val workGroupsX = (blocksX + 7) / 8
            val workGroupsY = (blocksY + 7) / 8
            
            GLES31.glDispatchCompute(workGroupsX, workGroupsY, 1)
            
            // Wait for completion
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            
            checkGlError("Dispatch compute")
            
            // Read back result
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, outputBuffer)
            val resultBuffer = ByteBuffer.allocateDirect(outputSize)
            resultBuffer.order(ByteOrder.nativeOrder())
            
            // Map buffer for reading
            val mapped = GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER,
                0, outputSize.toLong(),
                GLES31.GL_MAP_READ_BIT
            ) as? ByteBuffer
            
            if (mapped != null) {
                resultBuffer.put(mapped)
                GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            } else {
                Log.e(TAG, "Failed to map output buffer")
                return null
            }
            
            checkGlError("Read result")
            
            // Convert to byte array
            resultBuffer.position(0)
            val result = ByteArray(outputSize)
            resultBuffer.get(result)
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed", e)
            return null
        } finally {
            // Cleanup
            if (inputTexture != 0) {
                GLES31.glDeleteTextures(1, intArrayOf(inputTexture), 0)
            }
            if (outputBuffer != 0) {
                GLES31.glDeleteBuffers(1, intArrayOf(outputBuffer), 0)
            }
        }
    }
    
    private fun initEGL(): Boolean {
        // Get display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed")
            return false
        }
        
        // Initialize
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            return false
        }
        
        // Choose config with OpenGL ES 3.1 support
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x00000040, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            Log.e(TAG, "eglChooseConfig failed")
            return false
        }
        
        if (numConfigs[0] == 0) {
            Log.e(TAG, "No suitable EGL config found")
            return false
        }
        
        // Create context with OpenGL ES 3.1
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed")
            return false
        }
        
        // Create offscreen surface
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreatePbufferSurface failed")
            return false
        }
        
        // Make current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed")
            return false
        }
        
        // Check OpenGL ES version
        val glVersion = GLES31.glGetString(GLES31.GL_VERSION)
        Log.i(TAG, "OpenGL ES version: $glVersion")
        
        return true
    }
    
    private fun compileShader(): Boolean {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        if (shader == 0) {
            Log.e(TAG, "Failed to create compute shader")
            return false
        }
        
        GLES31.glShaderSource(shader, shaderSource)
        GLES31.glCompileShader(shader)
        
        val compileStatus = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compileStatus, 0)
        
        if (compileStatus[0] == 0) {
            val log = GLES31.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compilation failed: $log")
            GLES31.glDeleteShader(shader)
            return false
        }
        
        // Create program
        computeProgram = GLES31.glCreateProgram()
        if (computeProgram == 0) {
            Log.e(TAG, "Failed to create program")
            GLES31.glDeleteShader(shader)
            return false
        }
        
        GLES31.glAttachShader(computeProgram, shader)
        GLES31.glLinkProgram(computeProgram)
        
        val linkStatus = IntArray(1)
        GLES31.glGetProgramiv(computeProgram, GLES31.GL_LINK_STATUS, linkStatus, 0)
        
        if (linkStatus[0] == 0) {
            val log = GLES31.glGetProgramInfoLog(computeProgram)
            Log.e(TAG, "Program linking failed: $log")
            GLES31.glDeleteProgram(computeProgram)
            GLES31.glDeleteShader(shader)
            computeProgram = 0
            return false
        }
        
        GLES31.glDeleteShader(shader)
        
        // Get uniform locations
        uTexelWidthLoc = GLES31.glGetUniformLocation(computeProgram, "u_texelWidth")
        uTexelHeightLoc = GLES31.glGetUniformLocation(computeProgram, "u_texelHeight")
        uFlipYLoc = GLES31.glGetUniformLocation(computeProgram, "u_flipY")
        
        Log.i(TAG, "Compute shader compiled successfully")
        return true
    }
    
    private fun releaseEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
    
    private fun release() {
        if (computeProgram != 0) {
            GLES31.glDeleteProgram(computeProgram)
            computeProgram = 0
        }
        releaseEGL()
        isInitialized = false
    }
    
    override fun close() {
        release()
    }
    
    private fun checkGlError(operation: String) {
        val error = GLES31.glGetError()
        if (error != GLES31.GL_NO_ERROR) {
            Log.e(TAG, "$operation: GL error 0x${error.toString(16)}")
        }
    }
}
