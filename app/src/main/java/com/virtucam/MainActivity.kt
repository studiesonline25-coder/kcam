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
 * Fixed: Settings Injection, Media Preview, and Tab Reset issues.
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
            
            // Critical for SPA stability and preventing resets
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
            put("passthrough", config.isPassthroughMode)
            put("testPattern", config.isTestPatternMode)
        }
        webView.evaluateJavascript("if(window.onAndroidSync) window.onAndroidSync('$state')", null)
    }

    inner class AndroidInterface {
        @JavascriptInterface fun setEnabled(enabled: Boolean) { config.isEnabled = enabled }
        @JavascriptInterface fun setLivenessEnabled(enabled: Boolean) { config.isLivenessEnabled = enabled }
        @JavascriptInterface fun setTestPatternMode(enabled: Boolean) { config.isTestPatternMode = enabled }
        @JavascriptInterface fun setPassthroughMode(enabled: Boolean) { config.isPassthroughMode = enabled }
        @JavascriptInterface fun setBufferCapture(enabled: Boolean) { config.isBufferCaptureEnabled = enabled }
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
                webView.evaluateJavascript("window.onStreamConnecting && window.onStreamConnecting()", null)
                syncConfigToWeb()
            }
        }

        @JavascriptInterface
        fun disconnectStream() {
            config.isStream = false
            config.streamUrl = null
            runOnUiThread {
                webView.evaluateJavascript("window.onStreamDisconnected && window.onStreamDisconnected()", null)
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

                console.log("VIRTU-CAM BRIDGE BOOTING...");

                // Helper to find input/buttons by text or icon
                function findByText(text, selector = '*') {
                    return Array.from(document.querySelectorAll(selector)).find(el => el.innerText && el.innerText.includes(text));
                }

                // 1. SETTINGS PANEL INJECTION
                function injectSettingsUI() {
                    // Hide original "Investigation Tools" and specifically Passthrough/Test Pattern from main view
                    ['PASSTHROUGH', 'TEST PATTERN', 'INVESTIGATION TOOLS'].forEach(text => {
                        let el = findByText(text);
                        if (el) {
                            let parent = el.closest('div'); 
                            if (parent && parent.children.length < 5) parent.style.display = 'none';
                        }
                    });

                    // Detect Settings Panel (Look for "ADVANCED SETTINGS" header)
                    let settingsHeader = findByText('ADVANCED SETTINGS');
                    if (!settingsHeader) return;

                    let panel = settingsHeader.parentElement;
                    if (!panel || document.getElementById('vc-injected-settings')) return;

                    let container = document.createElement('div');
                    container.id = 'vc-injected-settings';
                    container.style.borderTop = '1px solid #333';
                    container.style.marginTop = '20px';
                    container.style.paddingTop = '10px';
                    
                    function createRow(label, stateKey, callback) {
                        let row = document.createElement('div');
                        row.style.display = 'flex'; row.style.justifyContent = 'space-between'; row.style.alignItems = 'center';
                        row.style.padding = '12px 0';
                        row.innerHTML = '<span>' + label + '</span>';
                        let toggle = document.createElement('div');
                        toggle.style.width = '44px'; toggle.style.height = '24px'; toggle.style.borderRadius = '12px';
                        toggle.style.backgroundColor = window.vcState && window.vcState[stateKey] ? '#22c55e' : '#444';
                        toggle.style.position = 'relative'; toggle.style.cursor = 'pointer';
                        let knob = document.createElement('div');
                        knob.style.width = '20px'; knob.style.height = '20px'; knob.style.borderRadius = '10px';
                        knob.style.backgroundColor = '#fff'; knob.style.position = 'absolute'; knob.style.top = '2px';
                        knob.style.left = window.vcState && window.vcState[stateKey] ? '22px' : '2px';
                        knob.style.transition = '0.2s';
                        toggle.appendChild(knob);
                        toggle.onclick = () => {
                            let newVal = !(window.vcState && window.vcState[stateKey]);
                            if (!window.vcState) window.vcState = {};
                            window.vcState[stateKey] = newVal;
                            toggle.style.backgroundColor = newVal ? '#22c55e' : '#444';
                            knob.style.left = newVal ? '22px' : '2px';
                            if (window.Android) window.Android[callback](newVal);
                        };
                        row.appendChild(toggle);
                        return row;
                    }

                    container.appendChild(createRow('Buffer Capture', 'bufferCapture', 'setBufferCapture'));
                    container.appendChild(createRow('Passthrough Mode', 'passthrough', 'setPassthroughMode'));
                    container.appendChild(createRow('Test Pattern', 'testPattern', 'setTestPatternMode'));
                    panel.appendChild(container);
                }

                // 2. MEDIA PREVIEW RESTORATION
                function updateMediaPreview() {
                    let selectArea = findByText('SELECT MEDIA');
                    if (!selectArea) return;
                    
                    let container = selectArea.closest('div');
                    if (!container || !window.vcState || !window.vcState.mediaPreview) return;

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

                // 3. TAB STABILITY
                // Intercept hash changes to prevent full reloads
                window.addEventListener('hashchange', () => {
                    console.log("Tab changed: " + window.location.hash);
                    // Force a re-run of injection logic
                    setTimeout(runAll, 100);
                });

                // 4. STREAM URL FIELD
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
                        injectSettingsUI();
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
