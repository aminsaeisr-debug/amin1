package com.srooyesh.seedcounter

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Maps the interactive PreviewView ROI into ML Kit's rotated image coordinates.
 * The CameraX ViewPort/cropRect is the source of truth so preview and analysis
 * use the same visible crop instead of separate fill-center approximations.
 */
object PreviewImageMapper {
    fun previewRectToImageRect(
        previewRect: Rect,
        previewWidth: Int,
        previewHeight: Int,
        rawImageWidth: Int,
        rawImageHeight: Int,
        cropRectRaw: Rect,
        rotationDegrees: Int
    ): Rect {
        if (previewWidth <= 0 || previewHeight <= 0 || rawImageWidth <= 0 || rawImageHeight <= 0) {
            return Rect(0, 0, max(1, rawImageWidth), max(1, rawImageHeight))
        }

        val safeRotation = ((rotationDegrees % 360) + 360) % 360
        val crop = Rect(
            cropRectRaw.left.coerceIn(0, rawImageWidth - 1),
            cropRectRaw.top.coerceIn(0, rawImageHeight - 1),
            cropRectRaw.right.coerceIn(1, rawImageWidth),
            cropRectRaw.bottom.coerceIn(1, rawImageHeight)
        )
        val rotatedVisible = rotateRawRectToImageSpace(crop, rawImageWidth, rawImageHeight, safeRotation)
        val rotatedWidth = if (safeRotation == 90 || safeRotation == 270) rawImageHeight else rawImageWidth
        val rotatedHeight = if (safeRotation == 90 || safeRotation == 270) rawImageWidth else rawImageHeight

        val leftFraction = previewRect.left.toFloat() / previewWidth.toFloat()
        val topFraction = previewRect.top.toFloat() / previewHeight.toFloat()
        val rightFraction = previewRect.right.toFloat() / previewWidth.toFloat()
        val bottomFraction = previewRect.bottom.toFloat() / previewHeight.toFloat()

        val mapped = RectF(
            rotatedVisible.left + rotatedVisible.width() * leftFraction,
            rotatedVisible.top + rotatedVisible.height() * topFraction,
            rotatedVisible.left + rotatedVisible.width() * rightFraction,
            rotatedVisible.top + rotatedVisible.height() * bottomFraction
        )

        val left = mapped.left.roundToInt().coerceIn(rotatedVisible.left, max(rotatedVisible.left + 1, rotatedVisible.right - 1))
        val top = mapped.top.roundToInt().coerceIn(rotatedVisible.top, max(rotatedVisible.top + 1, rotatedVisible.bottom - 1))
        val right = mapped.right.roundToInt().coerceIn(min(rotatedVisible.right, left + 1), rotatedVisible.right)
        val bottom = mapped.bottom.roundToInt().coerceIn(min(rotatedVisible.bottom, top + 1), rotatedVisible.bottom)

        return Rect(
            left.coerceIn(0, rotatedWidth - 1),
            top.coerceIn(0, rotatedHeight - 1),
            right.coerceIn(left + 1, rotatedWidth),
            bottom.coerceIn(top + 1, rotatedHeight)
        )
    }

    private fun rotateRawRectToImageSpace(rect: Rect, width: Int, height: Int, rotation: Int): Rect {
        return when (rotation) {
            90 -> Rect(
                height - rect.bottom,
                rect.left,
                height - rect.top,
                rect.right
            )
            180 -> Rect(
                width - rect.right,
                height - rect.bottom,
                width - rect.left,
                height - rect.top
            )
            270 -> Rect(
                rect.top,
                width - rect.right,
                rect.bottom,
                width - rect.left
            )
            else -> Rect(rect)
        }
    }
}
