package com.tamad.editss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

enum class CropMode {
    FREEFORM,
    SQUARE,
    PORTRAIT,
    LANDSCAPE
}

class CropView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    private val imageView: ImageView
    private val cropOverlay: CropOverlay

    init {
        imageView = ImageView(context)
        cropOverlay = CropOverlay(context)

        addView(imageView)
        addView(cropOverlay)
    }

    fun getImageView(): ImageView {
        return imageView
    }

    fun setCropMode(cropMode: CropMode) {
        cropOverlay.setCropMode(cropMode)
    }

    fun getCroppedRect(): RectF {
        return cropOverlay.getCroppedRect()
    }

    private class CropOverlay(context: Context) : View(context) {

        private val paint = Paint()
        private val cornerPaint = Paint()
        private var cropRect = RectF()
        private var currentCropMode = CropMode.FREEFORM
        private val matrixValues = FloatArray(9)
        private var imageMatrix = Matrix()

        init {
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.color = android.graphics.Color.WHITE
            paint.strokeWidth = 3f

            cornerPaint.isAntiAlias = true
            cornerPaint.style = Paint.Style.FILL
            cornerPaint.color = android.graphics.Color.WHITE
        }

        fun setCropMode(cropMode: CropMode) {
            currentCropMode = cropMode
            cropRect = RectF()
            invalidate()
        }

        fun getCroppedRect(): RectF {
            return cropRect
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(cropRect, paint)

            // Draw corners
            val cornerSize = 20f
            canvas.drawRect(cropRect.left, cropRect.top, cropRect.left + cornerSize, cropRect.top + cornerSize, cornerPaint)
            canvas.drawRect(cropRect.right - cornerSize, cropRect.top, cropRect.right, cropRect.top + cornerSize, cornerPaint)
            canvas.drawRect(cropRect.left, cropRect.bottom - cornerSize, cropRect.left + cornerSize, cropRect.bottom, cornerPaint)
            canvas.drawRect(cropRect.right - cornerSize, cropRect.bottom - cornerSize, cropRect.right, cropRect.bottom, cornerPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cropRect.left = x
                    cropRect.top = y
                    cropRect.right = x
                    cropRect.bottom = y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    updateCropRect(x, y)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    invalidate()
                }
                else -> return false
            }

            return true
        }

        private fun updateCropRect(x: Float, y: Float) {
            when (currentCropMode) {
                CropMode.FREEFORM -> {
                    cropRect.right = x
                    cropRect.bottom = y
                }
                CropMode.SQUARE -> {
                    val width = x - cropRect.left
                    val height = y - cropRect.top
                    val size = Math.max(width, height)
                    cropRect.right = cropRect.left + size
                    cropRect.bottom = cropRect.top + size
                }
                CropMode.PORTRAIT -> {
                    val width = x - cropRect.left
                    val height = width * 16 / 9
                    cropRect.right = x
                    cropRect.bottom = cropRect.top + height
                }
                CropMode.LANDSCAPE -> {
                    val height = y - cropRect.top
                    val width = height * 16 / 9
                    cropRect.right = cropRect.left + width
                    cropRect.bottom = y
                }
            }
        }
    }
}