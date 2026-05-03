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
 * Main Activity - Refined UI Injection
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
                // Helper to find the stream URL input
                function findStreamInput() {
                    var inputs = document.querySelectorAll('input');
                    for (var i = 0; i < inputs.length; i++) {
                        var p = (inputs[i].placeholder || "").toLowerCase();
                        if (p.includes('stream') || p.includes('rtmp') || p.includes('srt') || p.includes('url')) {
                            return inputs[i];
                        }
                    }
                    return null;
                }

                function injectPreviewUI() {
                    if (document.getElementById('virtucam-stream-preview')) return;
                    var targetInput = findStreamInput();
                    if (!targetInput) return;
                    
                    var parent = targetInput.parentElement;
                    while (parent && parent.tagName !== 'DIV') parent = parent.parentElement;
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
                        if (input.disabled) { input.disabled = false; input.style.opacity = '1'; }
                        input.oninput = function() { window.Android && window.Android.connectStream(this.value); };
                    }
                    
                    var buttons = document.querySelectorAll('button');
                    var connectBtn = null;
                    for (var i = 0; i < buttons.length; i++) {
                        if (buttons[i].innerText.toLowerCase().includes('connect')) { connectBtn = buttons[i]; break; }
                    }
                    if (connectBtn && !document.getElementById('virtucam-disconnect-btn')) {
                        var disconnectBtn = document.createElement('button');
                        disconnectBtn.id = 'virtucam-disconnect-btn'; disconnectBtn.innerText = 'Disconnect';
                        disconnectBtn.className = connectBtn.className; disconnectBtn.style.backgroundColor = '#dc3545';
                        disconnectBtn.style.marginTop = '8px'; disconnectBtn.style.display = 'none';
                        disconnectBtn.onclick = function() { window.Android && window.Android.disconnectStream(); };
                        connectBtn.parentElement.insertBefore(disconnectBtn, connectBtn.nextSibling);
                    }
                }

                function injectSettingsToggles() {
                    // Hide original controls first
                    var labels = ['Passthrough', 'Test Pattern', 'TestPattern', 'Pass-through'];
                    var all = document.querySelectorAll('div, span, label, p');
                    for (var i = 0; i < all.length; i++) {
                        for (var j = 0; j < labels.length; j++) {
                            if (all[i].innerText === labels[j]) {
                                var row = all[i].parentElement;
                                if (row && row.querySelectorAll('button, input[type="checkbox"]').length > 0) {
                                    row.style.display = 'none';
                                }
                            }
                        }
                    }

                    if (document.getElementById('virtucam-settings-ext')) return;
                    
                    var panels = document.querySelectorAll('div');
                    var targetPanel = null;
                    for (var i = 0; i < panels.length; i++) {
                        // Heuristic: panel with "Settings" header or appearing as a dialog
                        if (panels[i].innerText.includes('Settings') && panels[i].querySelectorAll('button, input[type="checkbox"]').length > 2) {
                            targetPanel = panels[i];
                            if (window.getComputedStyle(panels[i]).position === 'fixed' || window.getComputedStyle(panels[i]).position === 'absolute') break;
                        }
                    }
                    
                    if (!targetPanel) {
                        var lists = document.querySelectorAll('div, section');
                        for (var i = 0; i < lists.length; i++) {
                            if (lists[i].innerText.includes('Mirrored') || lists[i].innerText.includes('Liveness')) {
                                targetPanel = lists[i]; break;
                            }
                        }
                    }
                    
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
                    var bufferToggle = document.getElementById('toggle-buffer');
                    if (bufferToggle) {
                        bufferToggle.style.backgroundColor = state.bufferCapture ? '#28a745' : '#444';
                        bufferToggle.firstChild.style.left = state.bufferCapture ? '22px' : '2px';
                    }
                };

                var initInterval = setInterval(function() {
                    if (findStreamInput()) {
                        injectPreviewUI(); ensureUrlEditable();
                        clearInterval(initInterval);
                    }
                    // Monitor settings icon or panel
                    injectSettingsToggles();
                }, 1000);

                window.onStreamConnecting = function() {
                    var container = document.getElementById('virtucam-stream-preview');
                    var status = document.getElementById('virtucam-stream-status');
                    if (container) container.style.display = 'flex';
                    if (status) { status.innerText = 'Connecting...'; status.style.backgroundColor = '#ffc107'; status.style.color = '#000'; }
                };

                window.onStreamPreviewReady = function(uri) {
                    if (uri === 'pending') return;
                    var container = document.getElementById('virtucam-stream-preview');
                    var status = document.getElementById('virtucam-stream-status');
                    var img = document.getElementById('virtucam-stream-img');
                    if (container) container.style.display = 'flex';
                    if (status) { status.innerText = 'Live ✓'; status.style.backgroundColor = '#28a745'; status.style.color = '#fff'; }
                    if (img && uri) { img.src = uri + '?t=' + new Date().getTime(); img.style.display = 'block'; }
                };

                window.onStreamDisconnected = function() {
                    var container = document.getElementById('virtucam-stream-preview');
                    if (container) container.style.display = 'none';
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
