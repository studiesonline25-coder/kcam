package com.virtucam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.load
import coil.transform.RoundedCornersTransformation
import com.virtucam.data.VirtuCamConfig
import com.virtucam.databinding.ActivityMainBinding

/**
 * Main Activity
 * 
 * Provides UI for:
 * - Selecting a spoof image or video
 * - Enabling/disabling the camera hook
 * - Instructions for using with LSPatch
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var config: VirtuCamConfig
    private lateinit var imageLoader: ImageLoader
    private var previewPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    
    // Media picker launcher supporting both Images and Videos
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        config = VirtuCamConfig.getInstance(this)
        
        // Initialize Coil ImageLoader with VideoFrameDecoder support
        imageLoader = ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
        
        setupUI()
        loadSavedState()
    }
    
    private fun setupUI() {
        // Enable switch
        binding.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.isEnabled = isChecked
            updateStatusUI(isChecked)
        }
        
        // Image/Video preview tap
        binding.imagePreviewContainer.setOnClickListener {
            checkPermissionAndPickMedia()
        }
        
        // Pick media button
        binding.btnPickImage.setOnClickListener {
            checkPermissionAndPickMedia()
        }
        
        // Download LSPatch button
        binding.btnDownloadLSPatch.setOnClickListener {
            openLSPatchDownload()
        }
        
        // Stream URL Button
        binding.btnUseStream.setOnClickListener {
            val url = binding.etStreamUrl.text.toString().trim()
            if (url.isNotEmpty() && (url.startsWith("rtsp://") || url.startsWith("rtmp://") || url.startsWith("http"))) {
                handleStreamSelected(url)
            } else {
                Toast.makeText(this, "Please enter a valid RTSP/RTMP stream URL", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup compensation slider
        // Range 0.0x to 2.0x (progress 0..200)
        binding.seekCompensation.progress = (config.compensationFactor * 100).toInt()
        binding.tvCompensationValue.text = String.format("Stretch Factor: %.2fx", config.compensationFactor)
        
        binding.seekCompensation.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val factor = progress / 100f
                    config.compensationFactor = factor
                    binding.tvCompensationValue.text = String.format("Stretch Factor: %.2fx", factor)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                // Settings are already saved via setter in config
            }
        })

        // Mirror switch
        binding.mirrorSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.isMirrored = isChecked
            Toast.makeText(this, if (isChecked) "Mirror mode enabled" else "Mirror mode disabled", Toast.LENGTH_SHORT).show()
        }

        // Zoom SeekBar
        binding.seekZoom.progress = (config.zoomFactor * 100).toInt()
        binding.tvZoomValue.text = String.format("Zoom Factor: %.2fx", config.zoomFactor)
        binding.seekZoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val factor = progress / 100f
                    config.zoomFactor = factor
                    binding.tvZoomValue.text = String.format("Zoom Factor: %.2fx", factor)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // RTSP TCP switch
        binding.rtspTcpSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.rtspUseTcp = isChecked
            Toast.makeText(this, if (isChecked) "RTSP TCP mode enabled" else "RTSP UDP mode enabled", Toast.LENGTH_SHORT).show()
            // Re-start preview if it's currently showing a stream
            if (config.isStream && !config.streamUrl.isNullOrEmpty()) {
                handleStreamSelected(config.streamUrl!!)
            }
        }
    }
    
    private fun loadSavedState() {
        // Load enabled state
        binding.enableSwitch.isChecked = config.isEnabled
        updateStatusUI(config.isEnabled)
        
        // Load stream url
        val currentUrl = config.streamUrl
        if (currentUrl != null) {
            binding.etStreamUrl.setText(currentUrl)
        }
        
        // Load saved media
        if (config.isStream && currentUrl != null) {
            showStreamSelected(currentUrl)
        } else {
            config.spoofMediaUri?.let { uri ->
                showSelectedMedia(uri)
            }
        }
        
        // Load mirror state
        binding.mirrorSwitch.isChecked = config.isMirrored
        
        // Load zoom state
        binding.seekZoom.progress = (config.zoomFactor * 100).toInt()
        binding.tvZoomValue.text = String.format("Zoom Factor: %.2fx", config.zoomFactor)
        
        // Load RTSP state
        binding.rtspTcpSwitch.isChecked = config.rtspUseTcp
    }
    
    private fun updateStatusUI(enabled: Boolean) {
        val statusDot = binding.statusIndicator.background as? GradientDrawable
        
        val hasMedia = config.spoofMediaUri != null || (config.isStream && !config.streamUrl.isNullOrEmpty())
        
        if (enabled && hasMedia) {
            statusDot?.setColor(ContextCompat.getColor(this, R.color.status_enabled))
            binding.statusText.text = "VirtualCam is ACTIVE"
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.status_enabled))
        } else if (enabled) {
            statusDot?.setColor(ContextCompat.getColor(this, R.color.accent_red))
            binding.statusText.text = "Select media or a stream first!"
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        } else {
            statusDot?.setColor(ContextCompat.getColor(this, R.color.status_disabled))
            binding.statusText.text = "VirtualCam is DISABLED"
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }
    
    private fun checkPermissionAndPickMedia() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // PickVisualMedia doesn't necessarily need permissions, but good to check if using GetContent fallback
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                openMediaPicker()
            }
            shouldShowRequestPermissionRationale(permission) -> {
                Toast.makeText(this, "Permission needed to select scary media!", Toast.LENGTH_LONG).show()
                requestPermission.launch(permission)
            }
            else -> {
                requestPermission.launch(permission)
            }
        }
    }
    
    private fun openMediaPicker() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    
    private fun handleSelectedMedia(uri: Uri) {
        // Take persistable URI permission so we can access it later
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Some URIs don't support persistable permissions
        }
        
        // Determine whether it's an image or video
        val mimeType = contentResolver.getType(uri)
        val isVideo = mimeType?.startsWith("video/") == true
        
        // Save to config
        config.spoofMediaUri = uri
        config.isSpoofVideo = isVideo
        config.isStream = false
        
        // Show the media (extracting thumbnail if video)
        showSelectedMedia(uri)
        
        // Update status
        updateStatusUI(config.isEnabled)
        
        val typeStr = if (isVideo) "video" else "image"
        Toast.makeText(this, "Scary $typeStr selected! 👻", Toast.LENGTH_SHORT).show()
    }
    
    private fun handleStreamSelected(url: String) {
        config.streamUrl = url
        config.isStream = true
        
        showStreamSelected(url)
        updateStatusUI(config.isEnabled)
        
        Toast.makeText(this, "Live Stream set! 📡", Toast.LENGTH_SHORT).show()
    }
    
    private fun showStreamSelected(url: String) {
        binding.imagePlaceholder.visibility = View.GONE
        binding.imagePreview.visibility = View.GONE
        binding.playerPreview.visibility = View.VISIBLE
        
        // Stop any current video/stream
        previewPlayer?.stop()
        previewPlayer?.release()
        
        // Setup ExoPlayer for live preview with more resilient buffer settings (closer to VLC)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(2000, 8000, 1500, 2000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
            
        previewPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            
        binding.playerPreview.player = previewPlayer
        
        // Trim URL to prevent spaces from breaking detection (crucial for RTSP)
        val trimmedUrl = url.trim()
        val uri = Uri.parse(trimmedUrl)
        
        binding.tvStreamStatus.visibility = View.VISIBLE
        binding.tvStreamStatus.text = "Connecting..."

        previewPlayer?.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    androidx.media3.common.Player.STATE_BUFFERING -> {
                        binding.tvStreamStatus.visibility = View.VISIBLE
                        binding.tvStreamStatus.text = "Buffering... (Waiting for Keyframe)"
                    }
                    androidx.media3.common.Player.STATE_READY -> {
                        binding.tvStreamStatus.visibility = View.GONE
                    }
                    androidx.media3.common.Player.STATE_ENDED -> {
                        binding.tvStreamStatus.visibility = View.VISIBLE
                        binding.tvStreamStatus.text = "Stream Ended"
                    }
                    androidx.media3.common.Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.tvStreamStatus.visibility = View.VISIBLE
                binding.tvStreamStatus.text = "Error: ${error.localizedMessage}"
            }
        })
        
        val mediaSource = if (trimmedUrl.startsWith("rtsp", ignoreCase = true)) {
            androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                .setForceUseRtpTcp(config.rtspUseTcp)
                .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
        } else {
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this).createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
        }
        
        previewPlayer?.setMediaSource(mediaSource)
        previewPlayer?.prepare()
        previewPlayer?.play()
        
        Toast.makeText(this, "Playing Preview: $url", Toast.LENGTH_SHORT).show()
    }

    private fun showSelectedMedia(uri: Uri) {
        binding.playerPreview.visibility = View.GONE
        previewPlayer?.stop()
        
        binding.imagePlaceholder.visibility = View.GONE
        binding.imagePreview.visibility = View.VISIBLE
        
        // Coil with VideoFrameDecoder can load video URI directly for a thumbnail
        binding.imagePreview.load(uri, imageLoader) {
            crossfade(true)
            transformations(RoundedCornersTransformation(16f))
            error(android.R.drawable.ic_menu_report_image)
        }
    }
    
    private fun openLSPatchDownload() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://github.com/LSPosed/LSPatch/releases")
        }
        startActivity(intent)
    }

    override fun onStop() {
        super.onStop()
        previewPlayer?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewPlayer?.release()
        previewPlayer = null
    }
}
