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
        private var initialCropRect = RectF()
        private var currentCropMode = CropMode.FREEFORM
        private val matrixValues = FloatArray(9)
        private var imageMatrix = Matrix()
        
        // Touch handling
        private var isDragging = false
        private var isResizing = false
        private var dragHandle = 0 // 0=none, 1=top-left, 2=top-right, 3=bottom-left, 4=bottom-right, 5=center
        private var lastX = 0f
        private var lastY = 0f
        private val handleSize = 30f

        init {
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.color = android.graphics.Color.WHITE
            paint.strokeWidth = 3f

            cornerPaint.isAntiAlias = true
            cornerPaint.style = Paint.Style.FILL
            cornerPaint.color = android.graphics.Color.WHITE
            
            // Initialize with a centered crop rectangle
            initializeCropRectangle()
        }
        
        private fun initializeCropRectangle() {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            
            // Calculate initial crop rectangle based on mode
            initialCropRect = when (currentCropMode) {
                CropMode.SQUARE -> {
                    val size = Math.min(viewWidth, viewHeight) * 0.8f
                    val left = (viewWidth - size) / 2
                    val top = (viewHeight - size) / 2
                    RectF(left, top, left + size, top + size)
                }
                CropMode.PORTRAIT -> {
                    val aspectRatio = 9f / 16f
                    val height = viewHeight * 0.8f
                    val width = height * aspectRatio
                    val left = (viewWidth - width) / 2
                    val top = (viewHeight - height) / 2
                    RectF(left, top, left + width, top + height)
                }
                CropMode.LANDSCAPE -> {
                    val aspectRatio = 16f / 9f
                    val width = viewWidth * 0.8f
                    val height = width * aspectRatio
                    val left = (viewWidth - width) / 2
                    val top = (viewHeight - height) / 2
                    RectF(left, top, left + width, top + height)
                }
                else -> { // FREEFORM
                    RectF(viewWidth * 0.1f, viewHeight * 0.1f, viewWidth * 0.9f, viewHeight * 0.9f)
                }
            }
            cropRect = RectF(initialCropRect)
        }

        fun setCropMode(cropMode: CropMode) {
            currentCropMode = cropMode
            // Reset to initial position fitted to image
            initializeCropRectangle()
            invalidate()
        }

        fun getCroppedRect(): RectF {
            return cropRect
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            // Draw the main crop rectangle
            canvas.drawRect(cropRect, paint)

            // Draw corner handles
            val cornerSize = handleSize / 2
            canvas.drawRect(cropRect.left - cornerSize, cropRect.top - cornerSize, cropRect.left + cornerSize, cropRect.top + cornerSize, cornerPaint)
            canvas.drawRect(cropRect.right - cornerSize, cropRect.top - cornerSize, cropRect.right + cornerSize, cropRect.top + cornerSize, cornerPaint)
            canvas.drawRect(cropRect.left - cornerSize, cropRect.bottom - cornerSize, cropRect.left + cornerSize, cropRect.bottom + cornerSize, cornerPaint)
            canvas.drawRect(cropRect.right - cornerSize, cropRect.bottom - cornerSize, cropRect.right + cornerSize, cropRect.bottom + cornerSize, cornerPaint)
            
            // Draw center drag indicator
            val centerX = (cropRect.left + cropRect.right) / 2
            val centerY = (cropRect.top + cropRect.bottom) / 2
            val centerSize = handleSize / 3
            canvas.drawRect(centerX - centerSize, centerY - centerSize, centerX + centerSize, centerY + centerSize, cornerPaint)
        }
        
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Reinitialize crop rectangle when view size changes
            initializeCropRectangle()
        }
        
        private fun getTouchHandle(x: Float, y: Float): Int {
            // Check if touch is near corners or center
            val centerX = (cropRect.left + cropRect.right) / 2
            val centerY = (cropRect.top + cropRect.bottom) / 2
            val cornerThreshold = handleSize
            
            // Check corners
            if (Math.abs(x - cropRect.left) <= cornerThreshold && Math.abs(y - cropRect.top) <= cornerThreshold) {
                return 1 // top-left
            }
            if (Math.abs(x - cropRect.right) <= cornerThreshold && Math.abs(y - cropRect.top) <= cornerThreshold) {
                return 2 // top-right
            }
            if (Math.abs(x - cropRect.left) <= cornerThreshold && Math.abs(y - cropRect.bottom) <= cornerThreshold) {
                return 3 // bottom-left
            }
            if (Math.abs(x - cropRect.right) <= cornerThreshold && Math.abs(y - cropRect.bottom) <= cornerThreshold) {
                return 4 // bottom-right
            }
            
            // Check center
            if (Math.abs(x - centerX) <= cornerThreshold && Math.abs(y - centerY) <= cornerThreshold) {
                return 5 // center
            }
            
            return 0 // no handle
        }
        
        private fun constrainCropRect(newRect: RectF) {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            
            // Ensure rectangle stays within view bounds
            newRect.left = Math.max(0f, Math.min(newRect.left, viewWidth))
            newRect.top = Math.max(0f, Math.min(newRect.top, viewHeight))
            newRect.right = Math.max(0f, Math.min(newRect.right, viewWidth))
            newRect.bottom = Math.max(0f, Math.min(newRect.bottom, viewHeight))
            
            // Apply aspect ratio constraints based on mode
            when (currentCropMode) {
                CropMode.SQUARE -> {
                    val size = Math.max(newRect.width(), newRect.height())
                    val centerX = (newRect.left + newRect.right) / 2
                    val centerY = (newRect.top + newRect.bottom) / 2
                    newRect.left = centerX - size / 2
                    newRect.right = centerX + size / 2
                    newRect.top = centerY - size / 2
                    newRect.bottom = centerY + size / 2
                }
                CropMode.PORTRAIT -> {
                    val aspectRatio = 9f / 16f
                    val width = newRect.width()
                    val height = width / aspectRatio
                    val centerY = (newRect.top + newRect.bottom) / 2
                    newRect.top = centerY - height / 2
                    newRect.bottom = centerY + height / 2
                }
                CropMode.LANDSCAPE -> {
                    val aspectRatio = 16f / 9f
                    val height = newRect.height()
                    val width = height * aspectRatio
                    val centerX = (newRect.left + newRect.right) / 2
                    newRect.left = centerX - width / 2
                    newRect.right = centerX + width / 2
                }
                else -> { /* FREEFORM - no constraints */ }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragHandle = getTouchHandle(x, y)
                    if (dragHandle != 0) {
                        isDragging = true
                        lastX = x
                        lastY = y
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val deltaX = x - lastX
                        val deltaY = y - lastY
                        
                        val newRect = RectF(cropRect)
                        
                        when (dragHandle) {
                            1 -> { // top-left
                                newRect.left += deltaX
                                newRect.top += deltaY
                            }
                            2 -> { // top-right
                                newRect.right += deltaX
                                newRect.top += deltaY
                            }
                            3 -> { // bottom-left
                                newRect.left += deltaX
                                newRect.bottom += deltaY
                            }
                            4 -> { // bottom-right
                                newRect.right += deltaX
                                newRect.bottom += deltaY
                            }
                            5 -> { // center - move entire rectangle
                                newRect.left += deltaX
                                newRect.right += deltaX
                                newRect.top += deltaY
                                newRect.bottom += deltaY
                            }
                        }
                        
                        constrainCropRect(newRect)
                        cropRect = newRect
                        lastX = x
                        lastY = y
                        invalidate()
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    dragHandle = 0
                    return true
                }
                else -> return false
            }
        }

        // Removed old updateCropRect function - replaced with new drag-based approach
    }
}