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
        injectStreamPreviewBridge()
    }

    /**
     * Bridge from Android to Web
     */
    private fun syncConfigToWeb() {
        val file = java.io.File(filesDir, "stream_preview.jpg")
        val streamPreviewUriStr = if (file.exists()) "content://com.virtucam.provider/stream_preview/stream_preview.jpg" else ""
        val state = JSONObject().apply {
            put("isEnabled", config.isEnabled)
            put("zoom", config.zoomFactor)
            put("stretch", config.compensationFactor)
            put("mirrored", config.isMirrored)
            put("colorSwap", config.isColorSwapped)
            put("liveness", config.isLivenessEnabled)
            put("tcpMode", config.rtspUseTcp)
            put("streamUrl", config.streamUrl ?: "")
            put("isStream", config.isStream)
            put("mediaPreview", mediaPreviewBase64 ?: "")
            put("streamPreview", streamPreviewUriStr)
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
        fun setPassthroughMode(enabled: Boolean) {
            config.isPassthroughMode = enabled
        }

        @JavascriptInterface
        fun setRotationOffset(offset: Int) {
            config.rotationOffset = offset
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
            // Clear any stale stream preview
            try {
                java.io.File(filesDir, "stream_preview.jpg").delete()
            } catch (_: Exception) {}
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Stream Connected: $url", Toast.LENGTH_SHORT).show()
                // Tell Web UI to show "connecting" state for stream
                webView.evaluateJavascript("window.onStreamConnecting && window.onStreamConnecting()", null)
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
    private var streamPreviewUri: String? = null

    private fun generateMediaPreview(uri: Uri, isVideo: Boolean) {
        Thread {
            try {
                val bitmap = if (isVideo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(uri, android.util.Size(320, 240), null)
                    } else {
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

    /**
     * Saves a stream frame to internal storage and exposes it via ContentProvider.
     * Called by StreamPlayer when the first frame renders.
     */
    private fun saveStreamPreview(bitmap: android.graphics.Bitmap) {
        Thread {
            try {
                val file = java.io.File(filesDir, "stream_preview.jpg")
                file.outputStream().use { fos ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, fos)
                }
                bitmap.recycle()
                android.util.Log.d("VirtuCam_Web", "Stream preview saved: ${file.absolutePath}")
                runOnUiThread {
                    // Notify Web UI that stream preview is ready
                    val base64 = "data:image/jpeg;base64,not_needed"
                    webView.evaluateJavascript("window.onStreamPreviewReady && window.onStreamPreviewReady('$base64')", null)
                }
            } catch (e: Exception) {
                android.util.Log.e("VirtuCam_Web", "Stream preview save fail: ${e.message}")
            }
        }.start()
    }

    /**
     * Returns the ContentProvider URI for the stream preview image.
     */
    @JavascriptInterface
    fun getStreamPreviewUri(): String {
        val file = java.io.File(filesDir, "stream_preview.jpg")
        return if (file.exists()) {
            "content://com.virtucam.provider/stream_preview/stream_preview.jpg"
        } else {
            ""
        }
    }

    /**
     * Called from the Xposed module when a stream connection succeeds.
     * Triggers the StreamPlayer to capture and save a preview frame.
     * The actual capture happens inside VirtualRenderThread via StreamPlayer.onFirstFrame.
     */
    @JavascriptInterface
    fun requestStreamPreview() {
        // The preview is captured inside StreamPlayer on the GL render thread.
        // We just need to make sure the JS side knows to show a pending state.
        runOnUiThread {
            webView.evaluateJavascript("window.onStreamPreviewReady && window.onStreamPreviewReady('pending')", null)
        }
    }

    /**
     * Injects a JS bridge into the WebView that listens for stream preview updates.
     * When the Android side saves a stream preview JPEG, this JS code
     * finds the stream tab's preview img element and updates its src.
     */
    private fun injectStreamPreviewBridge() {
        // Poll every 2s for the stream preview file to appear,
        // then update the stream preview img element.
        val js = """
            (function() {
                var pollInterval;
                function tryInject() {
                    // Try multiple possible selectors for the stream preview element
                    var candidates = [
                        document.querySelector('img[alt*="stream"]'),
                        document.querySelector('img[src*="stream_preview"]'),
                        document.querySelector('.stream-preview img'),
                        document.querySelector('[class*="stream"] img'),
                    ];
                    var found = null;
                    for (var i = 0; i < candidates.length; i++) {
                        if (candidates[i]) { found = candidates[i]; break; }
                    }
                    if (!found) return; // Not rendered yet

                    clearInterval(pollInterval);

                    // If a content:// URI was injected, use it directly
                    var contentUri = 'content://com.virtucam.provider/stream_preview/stream_preview.jpg';
                    found.src = contentUri;
                    found.style.display = 'block';
                }

                pollInterval = setInterval(tryInject, 2000);

                // Also expose a global function the Android Java side can call directly
                window.onStreamPreviewReady = function(uri) {
                    if (uri === 'pending') return; // Connection in progress, ignore
                    var img = document.querySelector('img[alt*="stream"]') ||
                              document.querySelector('.stream-preview img') ||
                              document.querySelector('[class*="stream"] img');
                    if (img && uri) {
                        img.src = uri;
                        img.style.display = 'block';
                    }
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
