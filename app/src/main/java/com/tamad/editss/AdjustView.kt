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

    fun setEditViewModel(viewModel: EditViewModel) {
        adjustOverlay.setEditViewModel(viewModel)
    }

    fun getCurrentAdjustments(): Triple<Float, Float, Float> {
        return adjustOverlay.getCurrentAdjustments()
    }

    fun setAdjustments(brightness: Float, contrast: Float, saturation: Float) {
        adjustOverlay.setAdjustments(brightness, contrast, saturation)
    }

    fun clearAdjustments() {
        adjustOverlay.clearAdjustments()
    }

    fun undo() {
        adjustOverlay.undo()
    }

    fun redo(brightness: Float, contrast: Float, saturation: Float) {
        adjustOverlay.redo(brightness, contrast, saturation)
    }

    fun replayActions(actions: List<EditAction.Adjust>) {
        adjustOverlay.replayActions(actions)
    }

    private inner class AdjustOverlay(context: Context) : View(context) {

        private val paint = Paint()
        private val colorMatrix = ColorMatrix()
        private var brightness = 0f
        private var contrast = 1f
        private var saturation = 1f

        private var editViewModel: EditViewModel? = null
        private val adjustmentHistory = mutableListOf<Triple<Float, Float, Float>>()

        fun setEditViewModel(viewModel: EditViewModel) {
            editViewModel = viewModel
        }

        fun getCurrentAdjustments(): Triple<Float, Float, Float> {
            return Triple(brightness, contrast, saturation)
        }

        fun setAdjustments(b: Float, c: Float, s: Float) {
            brightness = b
            contrast = c
            saturation = s
            updateColorFilter()
        }

        fun clearAdjustments() {
            brightness = 0f
            contrast = 1f
            saturation = 1f
            adjustmentHistory.clear()
            updateColorFilter()
        }

        fun undo() {
            if (adjustmentHistory.isNotEmpty()) {
                adjustmentHistory.removeLast()
                if (adjustmentHistory.isNotEmpty()) {
                    val (lastBrightness, lastContrast, lastSaturation) = adjustmentHistory.last()
                    setAdjustments(lastBrightness, lastContrast, lastSaturation)
                } else {
                    clearAdjustments()
                }
            }
        }

        fun redo(b: Float, c: Float, s: Float) {
            setAdjustments(b, c, s)
            adjustmentHistory.add(Triple(b, c, s))
        }

        fun replayActions(actions: List<EditAction.Adjust>) {
            adjustmentHistory.clear()
            if (actions.isNotEmpty()) {
                val lastAction = actions.last()
                setAdjustments(lastAction.brightness, lastAction.contrast, lastAction.saturation)
                adjustmentHistory.addAll(actions.map { Triple(it.brightness, it.contrast, it.saturation) })
            } else {
                clearAdjustments()
            }
        }

        init {
            paint.isAntiAlias = true
        }

        fun setBrightness(value: Float) {
            brightness = value
            adjustmentHistory.add(Triple(brightness, contrast, saturation))
            editViewModel?.pushAction(EditAction.Adjust(brightness, contrast, saturation))
            updateColorFilter()
        }

        fun setContrast(value: Float) {
            contrast = value
            adjustmentHistory.add(Triple(brightness, contrast, saturation))
            editViewModel?.pushAction(EditAction.Adjust(brightness, contrast, saturation))
            updateColorFilter()
        }

        fun setSaturation(value: Float) {
            saturation = value
            adjustmentHistory.add(Triple(brightness, contrast, saturation))
            editViewModel?.pushAction(EditAction.Adjust(brightness, contrast, saturation))
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