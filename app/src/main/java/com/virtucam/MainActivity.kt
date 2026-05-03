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
            put("bufferCapture", config.isBufferCaptureEnabled)
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
        fun setBufferCapture(enabled: Boolean) {
            config.isBufferCaptureEnabled = enabled
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
        fun disconnectStream() {
            config.isStream = false
            config.streamUrl = null
            try {
                java.io.File(filesDir, "stream_preview.jpg").delete()
            } catch (_: Exception) {}
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Stream Disconnected", Toast.LENGTH_SHORT).show()
                webView.evaluateJavascript("window.onStreamDisconnected && window.onStreamDisconnected()", null)
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
                    val uri = "content://com.virtucam.provider/stream_preview/stream_preview.jpg"
                    webView.evaluateJavascript("window.onStreamPreviewReady && window.onStreamPreviewReady('$uri')", null)
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
                // 1. Feature 3: Inject Stream Preview Container
                function injectPreviewUI() {
                    if (document.getElementById('virtucam-stream-preview')) return;
                    
                    // Find a good place to inject. The React app probably has an input container for the stream.
                    var inputs = document.querySelectorAll('input[type="text"]');
                    var targetInput = null;
                    for (var i = 0; i < inputs.length; i++) {
                        if (inputs[i].placeholder.toLowerCase().includes('stream') || 
                            inputs[i].placeholder.includes('rtmp://')) {
                            targetInput = inputs[i];
                            break;
                        }
                    }
                    
                    if (!targetInput) return;
                    
                    var parent = targetInput.parentElement;
                    while (parent && parent.tagName !== 'DIV') {
                        parent = parent.parentElement;
                    }
                    if (!parent) return;

                    var container = document.createElement('div');
                    container.id = 'virtucam-stream-preview';
                    container.style.marginTop = '16px';
                    container.style.padding = '12px';
                    container.style.backgroundColor = '#1a1a1a';
                    container.style.borderRadius = '8px';
                    container.style.border = '1px solid #333';
                    container.style.display = 'none'; // Hidden initially
                    container.style.flexDirection = 'column';
                    container.style.gap = '8px';
                    
                    var header = document.createElement('div');
                    header.style.display = 'flex';
                    header.style.justifyContent = 'space-between';
                    header.style.alignItems = 'center';
                    
                    var title = document.createElement('span');
                    title.innerText = 'Stream Preview';
                    title.style.color = '#ccc';
                    title.style.fontSize = '14px';
                    
                    var status = document.createElement('span');
                    status.id = 'virtucam-stream-status';
                    status.innerText = 'Waiting...';
                    status.style.fontSize = '12px';
                    status.style.padding = '4px 8px';
                    status.style.borderRadius = '12px';
                    status.style.backgroundColor = '#333';
                    
                    header.appendChild(title);
                    header.appendChild(status);
                    
                    var img = document.createElement('img');
                    img.id = 'virtucam-stream-img';
                    img.style.width = '100%';
                    img.style.borderRadius = '4px';
                    img.style.display = 'none';
                    img.style.backgroundColor = '#000';
                    img.style.minHeight = '120px';
                    img.style.objectFit = 'contain';
                    
                    container.appendChild(header);
                    container.appendChild(img);
                    
                    // Append after the input container
                    parent.parentElement.insertBefore(container, parent.nextSibling);
                }

                // 2. Feature 2: Fix URL Editability
                function ensureUrlEditable() {
                    var inputs = document.querySelectorAll('input[type="text"]');
                    for (var i = 0; i < inputs.length; i++) {
                        if (inputs[i].placeholder.toLowerCase().includes('stream') || 
                            inputs[i].placeholder.includes('rtmp://')) {
                            // Force it to be editable even if React disabled it
                            if (inputs[i].disabled) {
                                inputs[i].disabled = false;
                                inputs[i].style.opacity = '1';
                            }
                        }
                    }
                    
                    // Find buttons to see if we need a disconnect button
                    var buttons = document.querySelectorAll('button');
                    var connectBtn = null;
                    for (var i = 0; i < buttons.length; i++) {
                        if (buttons[i].innerText.toLowerCase().includes('connect')) {
                            connectBtn = buttons[i];
                            break;
                        }
                    }
                    
                    if (connectBtn && !document.getElementById('virtucam-disconnect-btn')) {
                        var disconnectBtn = document.createElement('button');
                        disconnectBtn.id = 'virtucam-disconnect-btn';
                        disconnectBtn.innerText = 'Disconnect';
                        disconnectBtn.className = connectBtn.className;
                        disconnectBtn.style.backgroundColor = '#dc3545';
                        disconnectBtn.style.marginTop = '8px';
                        disconnectBtn.style.display = 'none';
                        disconnectBtn.onclick = function() {
                            window.Android && window.Android.disconnectStream();
                        };
                        connectBtn.parentElement.insertBefore(disconnectBtn, connectBtn.nextSibling);
                    }
                }

                // Inject UI once the React DOM is ready
                var initInterval = setInterval(function() {
                    var inputs = document.querySelectorAll('input[type="text"]');
                    if (inputs.length > 0) {
                        injectPreviewUI();
                        ensureUrlEditable();
                        clearInterval(initInterval);
                    }
                }, 1000);
                
                // Repeatedly ensure editability in case React re-renders and disables it
                setInterval(ensureUrlEditable, 2000);

                // 3. Status Callbacks for Android
                window.onStreamConnecting = function() {
                    var container = document.getElementById('virtucam-stream-preview');
                    var status = document.getElementById('virtucam-stream-status');
                    var img = document.getElementById('virtucam-stream-img');
                    var disconnectBtn = document.getElementById('virtucam-disconnect-btn');
                    
                    if (container) container.style.display = 'flex';
                    if (status) {
                        status.innerText = 'Connecting...';
                        status.style.backgroundColor = '#ffc107';
                        status.style.color = '#000';
                    }
                    if (img) img.style.opacity = '0.5';
                    if (disconnectBtn) disconnectBtn.style.display = 'block';
                };

                window.onStreamPreviewReady = function(uri) {
                    if (uri === 'pending') return;
                    var container = document.getElementById('virtucam-stream-preview');
                    var status = document.getElementById('virtucam-stream-status');
                    var img = document.getElementById('virtucam-stream-img');
                    
                    if (container) container.style.display = 'flex';
                    if (status) {
                        status.innerText = 'Live ✓';
                        status.style.backgroundColor = '#28a745';
                        status.style.color = '#fff';
                    }
                    if (img && uri) {
                        // Bust cache by adding timestamp
                        img.src = uri + (uri.includes('content://') ? '?t=' + new Date().getTime() : '');
                        img.style.display = 'block';
                        img.style.opacity = '1';
                    }
                };

                window.onStreamError = function(errorMsg) {
                    var container = document.getElementById('virtucam-stream-preview');
                    var status = document.getElementById('virtucam-stream-status');
                    
                    if (container) container.style.display = 'flex';
                    if (status) {
                        status.innerText = 'Error ✗';
                        status.style.backgroundColor = '#dc3545';
                        status.style.color = '#fff';
                    }
                    console.error("Stream Error: " + errorMsg);
                };
                
                window.onStreamDisconnected = function() {
                    var container = document.getElementById('virtucam-stream-preview');
                    var disconnectBtn = document.getElementById('virtucam-disconnect-btn');
                    var img = document.getElementById('virtucam-stream-img');
                    
                    if (container) container.style.display = 'none';
                    if (disconnectBtn) disconnectBtn.style.display = 'none';
                    if (img) img.src = '';
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
