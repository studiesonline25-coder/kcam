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
 * Main Activity - Ultra-Robust UI Bridge
 * Clean Slate: NO PROXIES. NO FFMPEG.
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView
    private lateinit var config: VirtuCamConfig
    
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { handleSelectedMedia(it) }
    }
    
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openMediaPicker()
        else Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show()
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
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                android.util.Log.d("VirtuCam_Web", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()}")
                return true
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                injectBridgeLogic()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                injectBridgeLogic()
                syncConfigToWeb()
            }
        }

        webView.addJavascriptInterface(AndroidInterface(), "Android")
        webView.loadUrl("file:///android_asset/newUI/index.html")
    }

    private fun syncConfigToWeb() {
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
            put("isStreamActive", config.streamUrl != null && config.isEnabled)
            put("mediaPreview", mediaPreviewBase64 ?: "")
            put("isSpoofVideo", config.isSpoofVideo)
            put("bufferCapture", config.isBufferCaptureEnabled)
            put("passthrough", config.isPassthroughMode)
            put("testPattern", config.isTestPatternMode)
            put("refineMode", config.isRefineMode)
        }
        webView.evaluateJavascript("if(window.onAndroidSync) window.onAndroidSync('$state')", null)
    }

    inner class AndroidInterface {
        @JavascriptInterface fun setEnabled(enabled: Boolean) { config.isEnabled = enabled }
        @JavascriptInterface fun setLivenessEnabled(enabled: Boolean) { config.isLivenessEnabled = enabled }
        @JavascriptInterface fun setTestPatternMode(enabled: Boolean) { config.isTestPatternMode = enabled }
        @JavascriptInterface fun setPassthroughMode(enabled: Boolean) { config.isPassthroughMode = enabled }
        @JavascriptInterface fun setBufferCapture(enabled: Boolean) { config.isBufferCaptureEnabled = enabled }
        @JavascriptInterface fun setRefineMode(enabled: Boolean) { config.isRefineMode = enabled }
        @JavascriptInterface fun setRotationOffset(offset: Int) { config.rotationOffset = offset }
        @JavascriptInterface fun updateZoom(valZoom: Float) { config.zoomFactor = valZoom }
        @JavascriptInterface fun updateStretch(valStretch: Float) { config.compensationFactor = valStretch }
        @JavascriptInterface fun setMirrored(mirrored: Boolean) { config.isMirrored = mirrored }
        @JavascriptInterface fun setColorSwap(swap: Boolean) { config.isColorSwapped = swap }
        @JavascriptInterface fun setTcpMode(tcp: Boolean) { config.rtspUseTcp = tcp }
        @JavascriptInterface fun pickMedia() { runOnUiThread { checkPermissionAndPickMedia() } }
        @JavascriptInterface fun requestSync() { runOnUiThread { syncConfigToWeb() } }

        @JavascriptInterface
        fun connectStream(url: String) {
            config.streamUrl = url
            config.isStream = true
            runOnUiThread {
                syncConfigToWeb()
            }
        }

        @JavascriptInterface
        fun disconnectStream() {
            config.isStream = false
            config.streamUrl = null
            runOnUiThread {
                syncConfigToWeb()
            }
        }
    }

    private fun checkPermissionAndPickMedia() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) openMediaPicker()
        else requestPermission.launch(permission)
    }
    
    private fun openMediaPicker() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    
    private fun handleSelectedMedia(uri: Uri) {
        val mimeType = contentResolver.getType(uri)
        val isVideo = mimeType?.startsWith("video/") == true
        config.spoofMediaUri = uri
        config.isSpoofVideo = isVideo
        config.isStream = false
        generateMediaPreview(uri, isVideo)
    }

    private var mediaPreviewBase64: String? = null

    private fun generateMediaPreview(uri: Uri, isVideo: Boolean) {
        Thread {
            try {
                val bitmap = if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, android.util.Size(320, 240), null)
                } else {
                    val inputStream = contentResolver.openInputStream(uri)
                    android.graphics.BitmapFactory.decodeStream(inputStream)
                }
                bitmap?.let {
                    val outputStream = java.io.ByteArrayOutputStream()
                    it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
                    mediaPreviewBase64 = "data:image/jpeg;base64," + android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
                    runOnUiThread { syncConfigToWeb() }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun injectBridgeLogic() {
        val js = """
            (function() {
                if (window.VIRTUCAM_BRIDGE_INITIALIZED) return;
                window.VIRTUCAM_BRIDGE_INITIALIZED = true;

                function findByText(text, selector = '*') {
                    return Array.from(document.querySelectorAll(selector)).find(el => el.innerText && el.innerText.includes(text));
                }

                function updateMediaPreview() {
                    let selectArea = findByText('SELECT MEDIA');
                    if (!selectArea) return;
                    let container = selectArea.closest('div');
                    if (!container || !window.vcState) return;

                    if (window.vcState.mediaPreview) {
                        let existingImg = container.querySelector('#vc-media-preview-img');
                        if (!existingImg) {
                            let icon = container.querySelector('svg');
                            if (icon) icon.style.display = 'none';
                            let img = document.createElement('img');
                            img.id = 'vc-media-preview-img';
                            img.style.width = '80px'; img.style.height = '80px'; img.style.borderRadius = '12px';
                            img.style.objectFit = 'cover';
                            container.prepend(img);
                            existingImg = img;
                        }
                        existingImg.src = window.vcState.mediaPreview;
                    }
                }

                function fixStreamField() {
                    let inputs = document.querySelectorAll('input');
                    inputs.forEach(input => {
                        let p = (input.placeholder || "").toLowerCase();
                        if (p.includes('rtmp') || p.includes('srt') || p.includes('stream')) {
                            input.disabled = false;
                            input.readOnly = false;
                            input.style.pointerEvents = 'auto';
                            if (!input.dataset.vcHooked) {
                                input.dataset.vcHooked = "true";
                                input.addEventListener('input', (e) => {
                                    if (window.Android) window.Android.connectStream(e.target.value);
                                });
                            }
                        }
                    });
                }

                window.onAndroidSync = function(stateStr) {
                    window.vcState = JSON.parse(stateStr);
                    runAll();
                };

                let isRunning = false;
                function runAll() {
                    if (isRunning) return;
                    isRunning = true;
                    try {
                        updateMediaPreview();
                        fixStreamField();
                    } finally {
                        setTimeout(() => isRunning = false, 200);
                    }
                }

                const observer = new MutationObserver(runAll);
                observer.observe(document.body, { childList: true, subtree: true });
                setInterval(runAll, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
