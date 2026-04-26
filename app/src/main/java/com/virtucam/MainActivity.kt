package com.virtucam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.virtucam.data.VirtuCamConfig
import org.json.JSONObject

/**
 * Main Activity - Updated for React/WebView Integration
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private lateinit var config: VirtuCamConfig
    
    // Media picker launcher
    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { handleSelectedMedia(it) }
    }
    
    // Permission launcher
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openMediaPicker()
        } else {
            Toast.makeText(this, "Permission required to select media", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        config = VirtuCamConfig.getInstance(this)
        setupWebView()
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            // Fix for file:/// URLs loading scripts on some devices
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            
            // Modern WebViews might need these too
            databaseEnabled = true
            setSupportMultipleWindows(false)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                android.util.Log.d("VirtuCam_Web", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                return true
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                syncConfigToWeb()
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                android.util.Log.e("VirtuCam_Web", "WebView Error: ${error?.description}")
            }
        }

        webView.addJavascriptInterface(AndroidInterface(), "Android")
        webView.loadUrl("file:///android_asset/newUI/index.html")
    }

    /**
     * Bridge from Android to Web
     */
    private fun syncConfigToWeb() {
        val state = JSONObject().apply {
            put("isEnabled", config.isEnabled)
            put("zoom", config.zoomFactor)
            put("stretch", config.compensationFactor)
            put("rotation", config.rotation)
            put("mirrored", config.isMirrored)
            put("colorSwap", config.isColorSwapped)
            put("liveness", config.isLivenessEnabled)
            put("tcpMode", config.rtspUseTcp)
            put("streamUrl", config.streamUrl ?: "")
            put("isStream", config.isStream)
            put("mediaPreview", mediaPreviewBase64 ?: "")
            put("isSpoofVideo", config.isSpoofVideo)
        }
        
        webView.evaluateJavascript("window.onAndroidSync('$state')", null)
    }

    /**
     * JS Interface for Web to Android communication
     */
    inner class AndroidInterface {
        @JavascriptInterface
        fun setEnabled(enabled: Boolean) {
            config.isEnabled = enabled
        }

        @JavascriptInterface
        fun setLivenessEnabled(enabled: Boolean) {
            config.isLivenessEnabled = enabled
        }

        @JavascriptInterface
        fun setTestPatternMode(enabled: Boolean) {
            config.isTestPatternMode = enabled
        }

        @JavascriptInterface
        fun updateZoom(valZoom: Float) {
            config.zoomFactor = valZoom
        }

        @JavascriptInterface
        fun updateStretch(valStretch: Float) {
            config.compensationFactor = valStretch
        }

        @JavascriptInterface
        fun setRotation(deg: Int) {
            config.rotation = deg
        }

        @JavascriptInterface
        fun setMirrored(mirrored: Boolean) {
            config.isMirrored = mirrored
        }

        @JavascriptInterface
        fun setColorSwap(swap: Boolean) {
            config.isColorSwapped = swap
        }

        @JavascriptInterface
        fun setTcpMode(tcp: Boolean) {
            config.rtspUseTcp = tcp
        }

        @JavascriptInterface
        fun pickMedia() {
            runOnUiThread {
                checkPermissionAndPickMedia()
            }
        }

        @JavascriptInterface
        fun connectStream(url: String) {
            config.streamUrl = url
            config.isStream = true
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Stream Connected: $url", Toast.LENGTH_SHORT).show()
                syncConfigToWeb()
            }
        }

        @JavascriptInterface
        fun requestSync() {
            runOnUiThread {
                syncConfigToWeb()
            }
        }
    }

    private fun checkPermissionAndPickMedia() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            openMediaPicker()
        } else {
            requestPermission.launch(permission)
        }
    }
    
    private fun openMediaPicker() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    
    private fun handleSelectedMedia(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        
        val mimeType = contentResolver.getType(uri)
        val isVideo = mimeType?.startsWith("video/") == true
        
        config.spoofMediaUri = uri
        config.isSpoofVideo = isVideo
        config.isStream = false
        
        generateMediaPreview(uri, isVideo)
        Toast.makeText(this, "Media Ready!", Toast.LENGTH_SHORT).show()
    }

    private var mediaPreviewBase64: String? = null

    private fun generateMediaPreview(uri: Uri, isVideo: Boolean) {
        val scope = this
        Thread {
            try {
                val bitmap = if (isVideo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(uri, android.util.Size(320, 240), null)
                    } else {
                         // Fallback for older Android
                         null
                    }
                } else {
                    val inputStream = contentResolver.openInputStream(uri)
                    android.graphics.BitmapFactory.decodeStream(inputStream)
                }

                bitmap?.let {
                    val outputStream = java.io.ByteArrayOutputStream()
                    it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
                    val bytes = outputStream.toByteArray()
                    mediaPreviewBase64 = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    runOnUiThread {
                        syncConfigToWeb()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VirtuCam_Web", "Thumbnail fail: ${e.message}")
            }
        }.start()
    }
}
