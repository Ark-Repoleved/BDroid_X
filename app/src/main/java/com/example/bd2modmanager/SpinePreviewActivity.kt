package com.example.bd2modmanager

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import java.io.File

class SpinePreviewActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var tempDir: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val skelPath = intent.getStringExtra("skelPath")
        val atlasPath = intent.getStringExtra("atlasPath")
        val tempDirPath = intent.getStringExtra("tempDirPath")

        if (tempDirPath != null) {
            tempDir = File(tempDirPath)
        }

        if (skelPath == null || atlasPath == null) {
            // Handle error, maybe show a toast and finish
            finish()
            return
        }

        webView = WebView(this)
        setContentView(webView)

        webView?.settings?.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            domStorageEnabled = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true // Needed for loading local files
        }

        // Encode paths to be safe for URL
        val encodedSkelPath = Uri.encode(skelPath)
        val encodedAtlasPath = Uri.encode(atlasPath)

        val url = "file:///android_asset/spine-viewer/preview.html?skel=$encodedSkelPath&atlas=$encodedAtlasPath"
        webView?.loadUrl(url)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the temporary directory
        tempDir?.let {
            if (it.exists()) {
                it.deleteRecursively()
            }
        }
        // Destroy the WebView to prevent memory leaks
        webView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.removeAllViews()
            it.destroy()
        }
        webView = null
    }
}
