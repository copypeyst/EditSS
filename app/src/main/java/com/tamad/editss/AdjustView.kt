package com.tamad.editss

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

class AdjustView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    private val imageView: ImageView
    private val adjustOverlay: AdjustOverlay

    init {
        imageView = ImageView(context)
        adjustOverlay = AdjustOverlay(context)

        addView(imageView)
        addView(adjustOverlay)
    }

    fun getImageView(): ImageView {
        return imageView
    }

    fun setBrightness(value: Float) {
        adjustOverlay.setBrightness(value)
    }

    fun setContrast(value: Float) {
        adjustOverlay.setContrast(value)
    }

    fun setSaturation(value: Float) {
        adjustOverlay.setSaturation(value)
    }

    private class AdjustOverlay(context: Context) : View(context) {

        private val paint = Paint()
        private val colorMatrix = ColorMatrix()
        private var brightness = 0f
        private var contrast = 1f
        private var saturation = 1f

        init {
            paint.isAntiAlias = true
        }

        fun setBrightness(value: Float) {
            brightness = value
            updateColorFilter()
        }

        fun setContrast(value: Float) {
            contrast = value
            updateColorFilter()
        }

        fun setSaturation(value: Float) {
            saturation = value
            updateColorFilter()
        }

        private fun updateColorFilter() {
            colorMatrix.reset()
            colorMatrix.setSaturation(saturation)
            val contrastMatrix = ColorMatrix()
            contrastMatrix.setScale(contrast, contrast, contrast, 1f)
            colorMatrix.postConcat(contrastMatrix)
            colorMatrix.setBrightness(brightness)
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // This view doesn't draw anything itself, it just applies the color filter to the ImageView below it.
            // However, to make the color filter apply, we need to draw something, even if it's transparent.
            canvas.drawPaint(paint)
        }
    }
}

// Extension function for ColorMatrix to set brightness
fun ColorMatrix.setBrightness(value: Float) {
    val array = floatArrayOf(
        1f, 0f, 0f, 0f, value,
        0f, 1f, 0f, 0f, value,
        0f, 0f, 1f, 0f, value,
        0f, 0f, 0f, 1f, 0f
    )
    set(array)
}