package com.virtucam.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Content Provider to share VirtuCam configuration with hooked apps
 * Also proxies file descriptors so target apps can read media without permissions
 */
class VirtuCamProvider : ContentProvider() {
    
    companion object {
        const val AUTHORITY = "com.virtucam.provider"
        const val PATH_CONFIG = "config"
        const val PATH_MEDIA = "media"
        const val PATH_FILE = "file"
        
        private const val CODE_CONFIG = 1
        private const val CODE_MEDIA = 2
        private const val CODE_FILE = 3
        
        val URI_CONFIG: Uri = Uri.parse("content://$AUTHORITY/$PATH_CONFIG")
        val URI_MEDIA: Uri = Uri.parse("content://$AUTHORITY/$PATH_MEDIA")
        val URI_FILE: Uri = Uri.parse("content://$AUTHORITY/$PATH_FILE")
        
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_CONFIG, CODE_CONFIG)
            addURI(AUTHORITY, PATH_MEDIA, CODE_MEDIA)
            addURI(AUTHORITY, PATH_FILE, CODE_FILE)
        }
    }
    
    private lateinit var config: VirtuCamConfig
    
    override fun onCreate(): Boolean {
        context?.let {
            config = VirtuCamConfig.getInstance(it)
        }
        return true
    }
    
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            CODE_CONFIG -> {
                MatrixCursor(arrayOf("enabled", "media_uri", "is_video", "is_stream", "stream_url", "target_apps", "compensation_factor", "is_mirrored")).apply {
                    addRow(arrayOf(
                        if (config.isEnabled) 1 else 0,
                        config.spoofMediaUri?.toString() ?: "",
                        if (config.isSpoofVideo) 1 else 0,
                        if (config.isStream) 1 else 0,
                        config.streamUrl ?: "",
                        config.targetApps.joinToString(","),
                        config.compensationFactor,
                        if (config.isMirrored) 1 else 0
                    ))
                }
            }
            CODE_MEDIA -> {
                MatrixCursor(arrayOf("uri", "is_video", "is_stream", "stream_url", "is_mirrored")).apply {
                    addRow(arrayOf(
                        config.spoofMediaUri?.toString() ?: "",
                        if (config.isSpoofVideo) 1 else 0,
                        if (config.isStream) 1 else 0,
                        config.streamUrl ?: "",
                        if (config.isMirrored) 1 else 0
                    ))
                }
            }
            else -> null
        }
    }
    
    /**
     * Proxies file descriptors to the target app so we bypass permission restrictions
     */
    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uriMatcher.match(uri) == CODE_FILE) {
            val mediaUri = config.spoofMediaUri
                ?: throw FileNotFoundException("No media selected in VirtuCam")
            
            return context?.contentResolver?.openFileDescriptor(mediaUri, "r")
        }
        return super.openFile(uri, mode)
    }
    
    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            CODE_CONFIG -> "vnd.android.cursor.item/vnd.virtucam.config"
            CODE_MEDIA -> "vnd.android.cursor.item/vnd.virtucam.media"
            CODE_FILE -> "video/*" // Simplified, can be video or image
            else -> null
        }
    }
    
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
