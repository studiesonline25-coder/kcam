package com.virtucam.hooks

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BufferDumper
 *
 * [INVESTIGATION TOOL] Saves RGBA pixel buffers to disk as PNG files so we
 * can directly inspect what rotation/orientation our pipeline produces at
 * each stage. This lets us answer questions like "did our renderer apply
 * the rotation we asked for?" with empirical evidence rather than
 * deductive math through a chain of OpenGL transforms.
 *
 * Output directory: /sdcard/Download/virtucam_audit/buffers/
 * Filename format: <tag>_<package>_pid<pid>_<timestamp>.png
 *
 * Usage from anywhere in a hook process:
 *   BufferDumper.dumpRgba(width, height, rgbaBytes, "bridge_1280x720")
 *
 * Files are written off-thread to avoid blocking the camera pipeline.
 */
object BufferDumper {

    private const val TAG = "VirtuCam_Dump"
    private const val DIR = "/sdcard/Download/virtucam_audit/buffers"

    /**
     * Dump a YUV_420_888 Image from an ImageReader as a PNG.
     * Converts YUV→RGB in-place then saves. Used by passthrough spy to
     * capture real hardware camera buffers.
     */
    fun dumpYuvImage(image: android.media.Image, tag: String) {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return
        try {
            val planes = image.planes
            val yBuf = planes[0].buffer
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer
            val yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixStride = planes[1].pixelStride

            val argbInts = IntArray(w * h)
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val yVal = (yBuf.get(row * yRowStride + col).toInt() and 0xFF)
                    val uvRow = row / 2
                    val uvCol = col / 2
                    val uVal = (uBuf.get(uvRow * uvRowStride + uvCol * uvPixStride).toInt() and 0xFF) - 128
                    val vVal = (vBuf.get(uvRow * uvRowStride + uvCol * uvPixStride).toInt() and 0xFF) - 128
                    val r = (yVal + 1.370705f * vVal).toInt().coerceIn(0, 255)
                    val g = (yVal - 0.337633f * uVal - 0.698001f * vVal).toInt().coerceIn(0, 255)
                    val b = (yVal + 1.732446f * uVal).toInt().coerceIn(0, 255)
                    argbInts[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val pkg = try {
                android.app.AndroidAppHelper.currentPackageName() ?: "unknown"
            } catch (_: Throwable) { "unknown" }
            val pid = android.os.Process.myPid()
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val filename = "${tag}_${pkg}_pid${pid}_${ts}.png"

            Thread {
                try {
                    val dir = File(DIR)
                    if (!dir.exists()) dir.mkdirs()
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(argbInts, 0, w, 0, 0, w, h)
                    val file = File(dir, filename)
                    FileOutputStream(file).use { os ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                    }
                    bitmap.recycle()
                    Log.i(TAG, "Dumped YUV buffer to ${file.absolutePath} (${w}x${h})")
                } catch (e: Throwable) {
                    Log.e(TAG, "dumpYuvImage failed", e)
                }
            }.also {
                it.name = "VirtuCam-BufferDump-YUV"
                it.isDaemon = true
                it.start()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "dumpYuvImage conversion failed", e)
        }
    }

    fun dumpRgba(width: Int, height: Int, rgba: ByteArray, tag: String) {
        if (width <= 0 || height <= 0) return
        if (rgba.size < width * height * 4) {
            Log.w(TAG, "dumpRgba: rgba size ${rgba.size} smaller than ${width}x${height}*4")
            return
        }
        val pkg = try {
            android.app.AndroidAppHelper.currentPackageName() ?: "unknown"
        } catch (_: Throwable) { "unknown" }
        val pid = android.os.Process.myPid()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "${tag}_${pkg}_pid${pid}_${ts}.png"

        Thread {
            try {
                val dir = File(DIR)
                if (!dir.exists()) dir.mkdirs()

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // ARGB_8888 in Android is actually packed as RGBA in memory order on little-endian
                // but Bitmap.copyPixelsFromBuffer expects ARGB. Convert RGBA -> ARGB via
                // byte swap of channel positions: src is R,G,B,A bytes; dest expects A,R,G,B for
                // ARGB_8888 in some conventions, or actually the same RGBA on little-endian.
                // Empirically, copyPixelsFromBuffer with RGBA bytes on a Config.ARGB_8888 bitmap
                // shows colors swapped. We do an explicit channel-aware copy:
                val argbInts = IntArray(width * height)
                var i = 0
                var j = 0
                while (i < argbInts.size) {
                    val r = rgba[j].toInt() and 0xFF
                    val g = rgba[j + 1].toInt() and 0xFF
                    val b = rgba[j + 2].toInt() and 0xFF
                    val a = rgba[j + 3].toInt() and 0xFF
                    argbInts[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    i++
                    j += 4
                }
                bitmap.setPixels(argbInts, 0, width, 0, 0, width, height)

                val file = File(dir, filename)
                FileOutputStream(file).use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                bitmap.recycle()
                Log.i(TAG, "Dumped buffer to ${file.absolutePath} (${width}x${height})")
            } catch (e: Throwable) {
                Log.e(TAG, "dumpRgba failed", e)
            }
        }.also {
            it.name = "VirtuCam-BufferDump"
            it.isDaemon = true
            it.start()
        }
    }
}
