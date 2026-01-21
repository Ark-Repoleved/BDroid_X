package com.example.bd2modmanager.gpu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock

/**
 * Bridge class for Python/Chaquopy to access GPU ASTC compression.
 * Provides static methods that can be called from Python code.
 */
object AstcCompressorBridge {
    
    private const val TAG = "AstcCompressorBridge"
    
    @Volatile
    private var encoder: GpuAstcEncoder? = null
    
    @Volatile
    private var applicationContext: Context? = null
    
    @Volatile
    private var isGpuAvailable: Boolean? = null
    
    // GPU 鎖，用於控制 GPU 訪問
    private val gpuLock = ReentrantLock()
    
    // 追蹤哪個線程持有鎖（用於調試）
    @Volatile
    private var lockHolderThread: String? = null
    
    /**
     * Initialize the bridge with application context.
     * Must be called from the main app before Python can use GPU compression.
     */
    @JvmStatic
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        Log.i(TAG, "Bridge initialized with context")
    }
    
    /**
     * Check if GPU compression is available.
     * Thread-safe and caches the result.
     */
    @JvmStatic
    fun isGpuAvailable(): Boolean {
        isGpuAvailable?.let { return it }
        
        val context = applicationContext
        if (context == null) {
            Log.w(TAG, "Context not initialized, GPU not available")
            return false
        }
        
        val supported = GpuCapability.isComputeShaderSupported(context)
        isGpuAvailable = supported
        return supported
    }
    
    /**
     * Try to acquire the GPU lock without blocking.
     * If the lock is available, acquire it and return true.
     * If the lock is held by another thread, return false immediately.
     * 
     * @return true if lock was acquired, false if GPU is busy
     */
    @JvmStatic
    fun tryAcquireGpu(): Boolean {
        val acquired = gpuLock.tryLock()
        if (acquired) {
            lockHolderThread = Thread.currentThread().name
            Log.d(TAG, "GPU lock acquired by ${lockHolderThread}")
        } else {
            Log.d(TAG, "GPU lock busy, held by ${lockHolderThread}")
        }
        return acquired
    }
    
    /**
     * Release the GPU lock.
     * Must be called after compression is complete.
     */
    @JvmStatic
    fun releaseGpuLock() {
        if (gpuLock.isHeldByCurrentThread) {
            Log.d(TAG, "GPU lock released by ${Thread.currentThread().name}")
            lockHolderThread = null
            gpuLock.unlock()
        } else {
            Log.w(TAG, "Attempted to release GPU lock not held by current thread")
        }
    }
    
    /**
     * Check if GPU is currently busy (locked by another thread).
     */
    @JvmStatic
    fun isGpuBusy(): Boolean {
        return gpuLock.isLocked && !gpuLock.isHeldByCurrentThread
    }
    
    /**
     * Compress an image file to ASTC format using GPU.
     * This method requires the caller to already hold the GPU lock via tryAcquireGpu().
     * 
     * @param inputPath Path to input PNG file
     * @param outputPath Path to write compressed ASTC data
     * @param flipY Whether to flip Y axis (default: true for Unity textures)
     * @return true if compression succeeded, false otherwise
     */
    @JvmStatic
    fun compressWithGpuLocked(inputPath: String, outputPath: String, flipY: Boolean = true): Boolean {
        if (!gpuLock.isHeldByCurrentThread) {
            Log.e(TAG, "compressWithGpuLocked called without holding GPU lock!")
            return false
        }
        return doCompressWithGpu(inputPath, outputPath, flipY)
    }
    
    /**
     * Compress an image file to ASTC format using GPU.
     * This method will block until the GPU lock is available.
     * For non-blocking behavior, use tryAcquireGpu() + compressWithGpuLocked() + releaseGpuLock().
     * 
     * @param inputPath Path to input PNG file
     * @param outputPath Path to write compressed ASTC data
     * @param flipY Whether to flip Y axis (default: true for Unity textures)
     * @return true if compression succeeded, false otherwise
     */
    @JvmStatic
    fun compressWithGpu(inputPath: String, outputPath: String, flipY: Boolean = true): Boolean {
        gpuLock.lock()
        try {
            lockHolderThread = Thread.currentThread().name
            return doCompressWithGpu(inputPath, outputPath, flipY)
        } finally {
            lockHolderThread = null
            gpuLock.unlock()
        }
    }
    
    private fun doCompressWithGpu(inputPath: String, outputPath: String, flipY: Boolean): Boolean {
        val context = applicationContext
        if (context == null) {
            Log.e(TAG, "Context not initialized")
            return false
        }
        
        if (!isGpuAvailable()) {
            Log.e(TAG, "GPU compression not available")
            return false
        }
        
        try {
            // Load bitmap
            val inputFile = File(inputPath)
            if (!inputFile.exists()) {
                Log.e(TAG, "Input file does not exist: $inputPath")
                return false
            }
            
            val options = BitmapFactory.Options()
            options.inPremultiplied = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            
            val bitmap = BitmapFactory.decodeFile(inputPath, options)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image: $inputPath")
                return false
            }
            
            try {
                // Initialize encoder if needed (reuse existing one)
                // EGL context unbinding is now handled in GpuAstcEncoder.compress()
                if (encoder == null) {
                    val shaderSource = loadShaderSource(context)
                    if (shaderSource == null) {
                        Log.e(TAG, "Failed to load shader source")
                        return false
                    }
                    
                    encoder = GpuAstcEncoder(shaderSource)
                    if (!encoder!!.initialize()) {
                        Log.e(TAG, "Failed to initialize GPU encoder")
                        encoder?.close()
                        encoder = null
                        return false
                    }
                }
                
                // Compress
                val startTime = System.currentTimeMillis()
                val result = encoder!!.compress(bitmap, flipY)
                val elapsed = System.currentTimeMillis() - startTime
                
                if (result == null) {
                    Log.e(TAG, "GPU compression returned null")
                    return false
                }
                
                Log.i(TAG, "GPU compression completed in ${elapsed}ms, size: ${result.size} bytes")
                
                // Write output
                val outputFile = File(outputPath)
                outputFile.parentFile?.mkdirs()
                
                FileOutputStream(outputFile).use { fos ->
                    fos.write(result)
                }
                
                return true
                
            } finally {
                bitmap.recycle()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "GPU compression failed", e)
            return false
        }
    }
    
    /**
     * Compress a bitmap to ASTC format and return raw bytes.
     * For use when bitmap is already in memory.
     */
    @JvmStatic
    @Synchronized
    fun compressBitmap(bitmap: Bitmap, flipY: Boolean = true): ByteArray? {
        val context = applicationContext
        if (context == null) {
            Log.e(TAG, "Context not initialized")
            return null
        }
        
        if (!isGpuAvailable()) {
            Log.e(TAG, "GPU compression not available")
            return null
        }
        
        try {
            // Initialize encoder if needed
            if (encoder == null) {
                val shaderSource = loadShaderSource(context)
                if (shaderSource == null) {
                    Log.e(TAG, "Failed to load shader source")
                    return null
                }
                
                encoder = GpuAstcEncoder(shaderSource)
                if (!encoder!!.initialize()) {
                    Log.e(TAG, "Failed to initialize GPU encoder")
                    encoder?.close()
                    encoder = null
                    return null
                }
            }
            
            return encoder!!.compress(bitmap, flipY)
            
        } catch (e: Exception) {
            Log.e(TAG, "GPU compression failed", e)
            return null
        }
    }
    
    /**
     * Release GPU resources.
     * Should be called when app is shutting down.
     */
    @JvmStatic
    @Synchronized
    fun release() {
        encoder?.close()
        encoder = null
        isGpuAvailable = null
        Log.i(TAG, "GPU encoder released")
    }
    
    private fun loadShaderSource(context: Context): String? {
        return try {
            context.assets.open("shaders/astc_encode.glsl").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load shader", e)
            null
        }
    }
}