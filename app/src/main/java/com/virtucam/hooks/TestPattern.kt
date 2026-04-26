package com.virtucam.hooks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * [INVESTIGATION] Generates a known-orientation test pattern bitmap.
 *
 * The pattern is:
 *   - White background
 *   - Red filled triangle at the top pointing UP, with "TOP" label below it
 *   - Blue "BOTTOM" label at the bottom
 *   - Green "L" near the left edge
 *   - Orange "R" near the right edge
 *   - Faint grid for visual reference
 *
 * Asymmetric on every axis (top/bottom, left/right) so any rotation OR mirror
 * is immediately visible to the eye in screenshots / dumped buffers.
 *
 * Use this instead of user-uploaded media when investigating rotation issues:
 *   1. Render the test pattern as the source.
 *   2. Take a screenshot of the host app's preview.
 *   3. Pull the corresponding buffer dump PNG from /sdcard/Download/virtucam_audit/buffers/
 *   4. Compare: source -> buffer -> screen reveals each transformation in the chain.
 */
object TestPattern {

    /**
     * Generate the test pattern at the requested dimensions.
     * Aspect ratio is preserved; output is exactly w x h.
     */
    fun generate(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 1. Background: white
        canvas.drawColor(Color.WHITE)

        // 2. Faint grid (every 1/8 of width and height)
        val gridPaint = Paint().apply {
            color = Color.argb(40, 0, 0, 0)
            strokeWidth = 1f
        }
        for (i in 1..7) {
            canvas.drawLine(w * i / 8f, 0f, w * i / 8f, h.toFloat(), gridPaint)
            canvas.drawLine(0f, h * i / 8f, w.toFloat(), h * i / 8f, gridPaint)
        }

        // 3. Red triangle pointing UP at top + "TOP" label
        val redPaint = Paint().apply {
            color = Color.RED
            isAntiAlias = true
        }
        val triHalfW = w / 14f
        val triH = h / 10f
        val triCx = w / 2f
        val triBaseY = triH * 1.4f
        val triPath = Path().apply {
            moveTo(triCx, triH * 0.4f)
            lineTo(triCx - triHalfW, triBaseY)
            lineTo(triCx + triHalfW, triBaseY)
            close()
        }
        canvas.drawPath(triPath, redPaint)

        val topPaint = Paint().apply {
            color = Color.RED
            textSize = h / 18f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText("TOP", w / 2f, triBaseY + h / 14f, topPaint)

        // 4. Blue "BOTTOM" label
        val bottomPaint = Paint().apply {
            color = Color.BLUE
            textSize = h / 18f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText("BOTTOM", w / 2f, h - h / 20f, bottomPaint)

        // 5. Green "L" near the LEFT edge (vertically centered)
        val leftPaint = Paint().apply {
            color = Color.rgb(0, 150, 0)
            textSize = h / 8f
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText("L", w / 30f, h / 2f + h / 24f, leftPaint)

        // 6. Orange "R" near the RIGHT edge (vertically centered)
        val rightPaint = Paint().apply {
            color = Color.rgb(255, 140, 0)
            textSize = h / 8f
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText("R", w - w / 30f, h / 2f + h / 24f, rightPaint)

        // 7. Small dimensions text in center for reference
        val centerPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = h / 36f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("${w} x ${h}", w / 2f, h / 2f, centerPaint)

        return bmp
    }
}
