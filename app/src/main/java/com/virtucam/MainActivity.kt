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
 * Main Activity - Professional UI Bridge with Debug Banner
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
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                android.util.Log.d("VirtuCam_Web", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()}")
                return true
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectStreamPreviewBridge()
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

    private fun injectStreamPreviewBridge() {
        val js = """
            (function() {
                console.log("VIRTU-CAM EXTENSION LOADING...");
                
                // 1. VISIBLE DEBUG BANNER (Proves code is running)
                if (!document.getElementById('virtucam-active-banner')) {
                    var banner = document.createElement('div');
                    banner.id = 'virtucam-active-banner';
                    banner.innerText = 'VIRTU-CAM UI EXTENSION ACTIVE';
                    banner.style.position = 'fixed'; banner.style.top = '0'; banner.style.left = '0'; banner.style.right = '0';
                    banner.style.backgroundColor = 'rgba(220, 53, 69, 0.9)'; banner.style.color = 'white';
                    banner.style.fontSize = '10px'; banner.style.textAlign = 'center'; banner.style.zIndex = '99999';
                    banner.style.padding = '2px'; banner.style.pointerEvents = 'none';
                    document.body.appendChild(banner);
                }

                function findStreamInput() {
                    var inputs = document.querySelectorAll('input');
                    for (var i = 0; i < inputs.length; i++) {
                        var p = (inputs[i].placeholder || "").toLowerCase();
                        if (p.includes('stream') || p.includes('rtmp') || p.includes('srt') || p.includes('url')) return inputs[i];
                    }
                    // Find input next to a "Connect" button
                    var buttons = document.querySelectorAll('button');
                    for (var i = 0; i < buttons.length; i++) {
                        if (buttons[i].innerText.toLowerCase().includes('connect')) {
                            var row = buttons[i].closest('div');
                            if (row) {
                                var input = row.querySelector('input');
                                if (input) return input;
                            }
                        }
                    }
                    return null;
                }

                function injectPreviewUI() {
                    if (document.getElementById('virtucam-stream-preview')) return;
                    var targetInput = findStreamInput();
                    if (!targetInput) return;
                    
                    var parent = targetInput.closest('div');
                    if (!parent) return;

                    var container = document.createElement('div');
                    container.id = 'virtucam-stream-preview';
                    container.style.marginTop = '16px'; container.style.padding = '12px';
                    container.style.backgroundColor = '#1a1a1a'; container.style.borderRadius = '8px';
                    container.style.border = '1px solid #333'; container.style.display = 'none';
                    container.style.flexDirection = 'column'; container.style.gap = '8px';
                    
                    var header = document.createElement('div');
                    header.style.display = 'flex'; header.style.justifyContent = 'space-between'; header.style.alignItems = 'center';
                    var title = document.createElement('span'); title.innerText = 'Stream Preview'; title.style.color = '#ccc'; title.style.fontSize = '14px';
                    var status = document.createElement('span'); status.id = 'virtucam-stream-status'; status.innerText = 'Waiting...';
                    status.style.fontSize = '12px'; status.style.padding = '4px 8px'; status.style.borderRadius = '12px'; status.style.backgroundColor = '#333';
                    
                    header.appendChild(title); header.appendChild(status);
                    var img = document.createElement('img'); img.id = 'virtucam-stream-img';
                    img.style.width = '100%'; img.style.borderRadius = '4px'; img.style.display = 'none';
                    img.style.backgroundColor = '#000'; img.style.minHeight = '120px'; img.style.objectFit = 'contain';
                    
                    container.appendChild(header); container.appendChild(img);
                    parent.parentElement.insertBefore(container, parent.nextSibling);
                }

                function ensureUrlEditable() {
                    var input = findStreamInput();
                    if (input) {
                        input.disabled = false; input.readOnly = false;
                        input.style.opacity = '1'; input.style.pointerEvents = 'auto';
                        if (!input.dataset.hooked) {
                            input.dataset.hooked = "true";
                            input.addEventListener('input', function() {
                                if (window.Android) window.Android.connectStream(this.value);
                            });
                        }
                    }
                }

                function injectSettingsToggles() {
                    // Hide original controls
                    ['Passthrough', 'Test Pattern'].forEach(label => {
                        document.querySelectorAll('div, span, label, p').forEach(el => {
                            if (el.innerText === label) {
                                var row = el.closest('div');
                                if (row && row.querySelectorAll('button, input').length > 0) row.style.display = 'none';
                            }
                        });
                    });

                    if (document.getElementById('virtucam-settings-ext')) return;
                    
                    // Find ANY visible panel containing "Settings" OR a panel opened from the top-right
                    var panels = Array.from(document.querySelectorAll('div')).filter(d => 
                        (d.innerText.includes('Settings') || d.innerText.includes('Mirrored')) && 
                        window.getComputedStyle(d).display !== 'none' &&
                        d.innerText.length < 1000 // Heuristic: small enough to be a menu
                    );
                    
                    panels.sort((a,b) => a.innerText.length - b.innerText.length);
                    var targetPanel = panels[0];

                    if (!targetPanel) return;

                    var extContainer = document.createElement('div');
                    extContainer.id = 'virtucam-settings-ext';
                    extContainer.style.borderTop = '1px solid #333'; extContainer.style.marginTop = '12px'; extContainer.style.paddingTop = '12px';
                    
                    function createToggle(label, id, initialValue, callbackName) {
                        var row = document.createElement('div');
                        row.style.display = 'flex'; row.style.justifyContent = 'space-between'; row.style.alignItems = 'center'; row.style.marginBottom = '12px';
                        var text = document.createElement('span'); text.innerText = label; text.style.fontSize = '14px'; text.style.color = '#ccc';
                        var toggle = document.createElement('div'); toggle.id = 'toggle-' + id;
                        toggle.style.width = '40px'; toggle.style.height = '20px'; toggle.style.borderRadius = '10px';
                        toggle.style.backgroundColor = initialValue ? '#28a745' : '#444';
                        toggle.style.position = 'relative'; toggle.style.cursor = 'pointer';
                        var knob = document.createElement('div'); knob.style.width = '16px'; knob.style.height = '16px'; knob.style.borderRadius = '50%';
                        knob.style.backgroundColor = '#fff'; knob.style.position = 'absolute'; knob.style.top = '2px';
                        knob.style.left = initialValue ? '22px' : '2px'; knob.style.transition = 'left 0.2s';
                        toggle.appendChild(knob); row.appendChild(text); row.appendChild(toggle);
                        var current = initialValue;
                        toggle.onclick = function() {
                            current = !current;
                            toggle.style.backgroundColor = current ? '#28a745' : '#444';
                            knob.style.left = current ? '22px' : '2px';
                            if (window.Android && window.Android[callbackName]) window.Android[callbackName](current);
                        };
                        return row;
                    }
                    var state = window.virtucamState || {};
                    extContainer.appendChild(createToggle('Buffer Capture', 'buffer', state.bufferCapture !== false, 'setBufferCapture'));
                    extContainer.appendChild(createToggle('Passthrough Mode', 'passthrough', !!state.passthrough, 'setPassthroughMode'));
                    extContainer.appendChild(createToggle('Test Pattern', 'testpattern', !!state.testPattern, 'setTestPatternMode'));
                    targetPanel.appendChild(extContainer);
                }

                window.onAndroidSync = function(stateStr) {
                    var state = JSON.parse(stateStr);
                    window.virtucamState = state;
                    ['buffer', 'passthrough', 'testpattern'].forEach(id => {
                        var t = document.getElementById('toggle-' + id);
                        if (t) {
                            var val = id === 'buffer' ? state.bufferCapture : (id === 'passthrough' ? state.passthrough : state.testPattern);
                            t.style.backgroundColor = val ? '#28a745' : '#444';
                            t.firstChild.style.left = val ? '22px' : '2px';
                        }
                    });
                };

                var observer = new MutationObserver(() => {
                    injectPreviewUI(); ensureUrlEditable(); injectSettingsToggles();
                });
                observer.observe(document.body, { childList: true, subtree: true });
                
                setInterval(() => {
                    injectPreviewUI(); ensureUrlEditable(); injectSettingsToggles();
                }, 2000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
