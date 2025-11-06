package com.tamad.editss

import android.content.Context
import android.graphics.*
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
        private val edgePaint = Paint()
        private val gridPaint = Paint()
        private val overlayPaint = Paint()
        
        private var cropRect = RectF()
        private var initialCropRect = RectF()
        private var currentCropMode = CropMode.FREEFORM
        
        // Touch handling - 8 edges + 4 corners + center = 13 handles
        private var isDragging = false
        private var dragHandle = 0 // 0=none, 1-4=edges(top,right,bottom,left), 5-8=corners, 9=center
        private var lastX = 0f
        private var lastY = 0f
        private val handleSize = 60f
        private val edgeThickness = 40f
        private val centerSize = 120f
        
        // Image bounds for proper constraint checking
        private var imageBounds = RectF()
        private var originalCenterX = 0f
        private var originalCenterY = 0f
        private var originalWidth = 0f
        private var originalHeight = 0f

        init {
            // Main marquee border
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = 3f

            // Corner handles (5% opacity)
            cornerPaint.isAntiAlias = true
            cornerPaint.style = Paint.Style.FILL
            cornerPaint.color = Color.WHITE
            cornerPaint.alpha = 13

            // Edge handles (5% opacity)
            edgePaint.isAntiAlias = true
            edgePaint.style = Paint.Style.FILL
            edgePaint.color = Color.WHITE
            edgePaint.alpha = 13

            // Grid lines
            gridPaint.isAntiAlias = true
            gridPaint.style = Paint.Style.STROKE
            gridPaint.color = Color.WHITE
            gridPaint.strokeWidth = 1f
            gridPaint.alpha = 120

            // Semi-transparent overlay outside marquee
            overlayPaint.isAntiAlias = true
            overlayPaint.style = Paint.Style.FILL
            overlayPaint.color = Color.parseColor("#66000000") // Semi-transparent black
        }
        
        private fun initializeCropRectangle() {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            
            // Handle case where view hasn't been measured yet
            val effectiveWidth = if (viewWidth == 0f) 1000f else viewWidth
            val effectiveHeight = if (viewHeight == 0f) 1000f else viewHeight
            
            // Set image bounds based on effective dimensions (or use actual image bounds if available)
            if (imageBounds.width() == 0f || imageBounds.height() == 0f) {
                imageBounds = RectF(0f, 0f, effectiveWidth, effectiveHeight)
            }
            
            // Store original center for aspect ratio constraints
            originalCenterX = (imageBounds.left + imageBounds.right) / 2
            originalCenterY = (imageBounds.top + imageBounds.bottom) / 2
            originalWidth = imageBounds.width()
            originalHeight = imageBounds.height()
            
            // Calculate initial crop rectangle based on mode
            initialCropRect = when (currentCropMode) {
                CropMode.SQUARE -> {
                    val size = Math.min(effectiveWidth, effectiveHeight) * 0.8f
                    val left = (effectiveWidth - size) / 2
                    val top = (effectiveHeight - size) / 2
                    RectF(left, top, left + size, top + size)
                }
                CropMode.PORTRAIT -> {
                    val aspectRatio = 9f / 16f // Width:Height = 9:16 for portrait
                    val height = effectiveHeight * 0.8f
                    val width = height * aspectRatio
                    val left = (effectiveWidth - width) / 2
                    val top = (effectiveHeight - height) / 2
                    RectF(left, top, left + width, top + height)
                }
                CropMode.LANDSCAPE -> {
                    val aspectRatio = 16f / 9f // Width:Height = 16:9 for landscape
                    val width = effectiveWidth * 0.8f
                    val height = width / aspectRatio
                    val left = (effectiveWidth - width) / 2
                    val top = (effectiveHeight - height) / 2
                    RectF(left, top, left + width, top + height)
                }
                else -> { // FREEFORM
                    RectF(effectiveWidth * 0.1f, effectiveHeight * 0.1f, effectiveWidth * 0.9f, effectiveHeight * 0.9f)
                }
            }
            cropRect = RectF(initialCropRect)
        }

        fun setCropMode(cropMode: CropMode) {
            currentCropMode = cropMode
            // Always reset to initial position fitted to image
            initializeCropRectangle()
            invalidate()
        }
        
        fun setImageBounds(imageRect: RectF) {
            imageBounds = RectF(imageRect)
            // Reinitialize with new image bounds
            initializeCropRectangle()
            invalidate()
        }

        fun getCroppedRect(): RectF {
            return cropRect
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            // Draw semi-transparent overlay outside marquee
            drawOverlay(canvas)
            
            // Draw the main crop rectangle
            canvas.drawRect(cropRect, paint)

            // Draw 3x3 grid lines inside the marquee
            drawGrid(canvas)
            
            // Draw edge handles (top, right, bottom, left)
            drawEdgeHandles(canvas)
            
            // Draw corner handles
            drawCornerHandles(canvas)
            
            // Draw center move handle
            drawCenterHandle(canvas)
        }
        
        private fun drawOverlay(canvas: Canvas) {
            // Create overlay excluding the crop rectangle area
            val path = Path()
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            path.addRect(cropRect, Path.Direction.CCW)
            
            canvas.drawPath(path, overlayPaint)
        }
        
        private fun drawGrid(canvas: Canvas) {
            // Vertical grid lines (split marquee into 3 columns)
            val left = cropRect.left
            val right = cropRect.right
            val top = cropRect.top
            val bottom = cropRect.bottom
            val width = right - left
            val height = bottom - top
            
            val thirdWidth = width / 3
            val thirdHeight = height / 3
            
            // Vertical lines
            canvas.drawLine(left + thirdWidth, top, left + thirdWidth, bottom, gridPaint)
            canvas.drawLine(left + 2 * thirdWidth, top, left + 2 * thirdWidth, bottom, gridPaint)
            
            // Horizontal lines
            canvas.drawLine(left, top + thirdHeight, right, top + thirdHeight, gridPaint)
            canvas.drawLine(left, top + 2 * thirdHeight, right, top + 2 * thirdHeight, gridPaint)
        }
        
        private fun drawEdgeHandles(canvas: Canvas) {
            val left = cropRect.left
            val right = cropRect.right
            val top = cropRect.top
            val bottom = cropRect.bottom
            
            // Top edge
            canvas.drawRect(left + handleSize, top - edgeThickness / 2, right - handleSize, top + edgeThickness / 2, edgePaint)
            // Right edge
            canvas.drawRect(right - edgeThickness / 2, top + handleSize, right + edgeThickness / 2, bottom - handleSize, edgePaint)
            // Bottom edge
            canvas.drawRect(left + handleSize, bottom - edgeThickness / 2, right - handleSize, bottom + edgeThickness / 2, edgePaint)
            // Left edge
            canvas.drawRect(left - edgeThickness / 2, top + handleSize, left + edgeThickness / 2, bottom - handleSize, edgePaint)
        }
        
        private fun drawCornerHandles(canvas: Canvas) {
            val left = cropRect.left
            val right = cropRect.right
            val top = cropRect.top
            val bottom = cropRect.bottom
            val cornerSize = handleSize / 2
            
            // Top-left
            canvas.drawRect(left - cornerSize, top - cornerSize, left + cornerSize, top + cornerSize, cornerPaint)
            // Top-right
            canvas.drawRect(right - cornerSize, top - cornerSize, right + cornerSize, top + cornerSize, cornerPaint)
            // Bottom-left
            canvas.drawRect(left - cornerSize, bottom - cornerSize, left + cornerSize, bottom + cornerSize, cornerPaint)
            // Bottom-right
            canvas.drawRect(right - cornerSize, bottom - cornerSize, right + cornerSize, bottom + cornerSize, cornerPaint)
        }
        
        private fun drawCenterHandle(canvas: Canvas) {
            val centerX = (cropRect.left + cropRect.right) / 2
            val centerY = (cropRect.top + cropRect.bottom) / 2
            val size = centerSize / 2
            
            // Make center handle more transparent (5% opacity)
            val centerPaint = Paint(cornerPaint)
            centerPaint.alpha = 13
            
            canvas.drawRect(centerX - size, centerY - size, centerX + size, centerY + size, centerPaint)
        }
        
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Reinitialize crop rectangle when view size changes
            initializeCropRectangle()
        }
        
        private fun getTouchHandle(x: Float, y: Float): Int {
            val left = cropRect.left
            val right = cropRect.right
            val top = cropRect.top
            val bottom = cropRect.bottom
            val centerX = (left + right) / 2
            val centerY = (top + bottom) / 2
            
            val cornerThreshold = handleSize
            val edgeThreshold = edgeThickness / 2 + handleSize
            
            // Check edge handles first (larger areas)
            // Top edge
            if (y in (top - edgeThreshold)..(top + edgeThreshold) && 
                x in (left + cornerThreshold)..(right - cornerThreshold)) {
                return 1 // top edge
            }
            
            // Right edge
            if (x in (right - edgeThreshold)..(right + edgeThreshold) && 
                y in (top + cornerThreshold)..(bottom - cornerThreshold)) {
                return 2 // right edge
            }
            
            // Bottom edge
            if (y in (bottom - edgeThreshold)..(bottom + edgeThreshold) && 
                x in (left + cornerThreshold)..(right - cornerThreshold)) {
                return 3 // bottom edge
            }
            
            // Left edge
            if (x in (left - edgeThreshold)..(left + edgeThreshold) && 
                y in (top + cornerThreshold)..(bottom - cornerThreshold)) {
                return 4 // left edge
            }
            
            // Check corner handles
            if (Math.abs(x - left) <= cornerThreshold && Math.abs(y - top) <= cornerThreshold) {
                return 5 // top-left
            }
            if (Math.abs(x - right) <= cornerThreshold && Math.abs(y - top) <= cornerThreshold) {
                return 6 // top-right
            }
            if (Math.abs(x - left) <= cornerThreshold && Math.abs(y - bottom) <= cornerThreshold) {
                return 7 // bottom-left
            }
            if (Math.abs(x - right) <= cornerThreshold && Math.abs(y - bottom) <= cornerThreshold) {
                return 8 // bottom-right
            }
            
            // Check center
            if (Math.abs(x - centerX) <= centerSize && Math.abs(y - centerY) <= centerSize) {
                return 9 // center
            }
            
            return 0 // no handle
        }
        
        private fun constrainCropRect(newRect: RectF) {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            
            when (currentCropMode) {
                CropMode.SQUARE -> {
                    // 1:1 aspect ratio - calculate size based on center and aspect ratio
                    val centerX = (newRect.left + newRect.right) / 2
                    val centerY = (newRect.top + newRect.bottom) / 2
                    val size = Math.min(newRect.width(), newRect.height())
                    
                    // Apply 1:1 aspect ratio centered at the center point
                    newRect.left = centerX - size / 2
                    newRect.right = centerX + size / 2
                    newRect.top = centerY - size / 2
                    newRect.bottom = centerY + size / 2
                }
                CropMode.PORTRAIT -> {
                    // 9:16 aspect ratio (width:height)
                    val centerX = (newRect.left + newRect.right) / 2
                    val centerY = (newRect.top + newRect.bottom) / 2
                    val width = newRect.width()
                    val height = width * (16f / 9f) // Height = width * (16/9) for 9:16 ratio
                    
                    // Apply aspect ratio centered at center point
                    newRect.left = centerX - width / 2
                    newRect.right = centerX + width / 2
                    newRect.top = centerY - height / 2
                    newRect.bottom = centerY + height / 2
                }
                CropMode.LANDSCAPE -> {
                    // 16:9 aspect ratio (width:height)
                    val centerX = (newRect.left + newRect.right) / 2
                    val centerY = (newRect.top + newRect.bottom) / 2
                    val height = newRect.height()
                    val width = height * (16f / 9f) // Width = height * (16/9) for 16:9 ratio
                    
                    // Apply aspect ratio centered at center point
                    newRect.left = centerX - width / 2
                    newRect.right = centerX + width / 2
                    newRect.top = centerY - height / 2
                    newRect.bottom = centerY + height / 2
                }
                else -> { /* FREEFORM - no aspect ratio constraints */ }
            }
            
            // Constrain to image bounds (not just canvas bounds)
            if (imageBounds.width() > 0 && imageBounds.height() > 0) {
                // Use actual image bounds
                newRect.left = Math.max(imageBounds.left, Math.min(newRect.left, imageBounds.right))
                newRect.top = Math.max(imageBounds.top, Math.min(newRect.top, imageBounds.bottom))
                newRect.right = Math.max(imageBounds.left, Math.min(newRect.right, imageBounds.right))
                newRect.bottom = Math.max(imageBounds.top, Math.min(newRect.bottom, imageBounds.bottom))
            } else {
                // Fallback to canvas bounds if no image bounds available
                newRect.left = Math.max(0f, Math.min(newRect.left, viewWidth))
                newRect.top = Math.max(0f, Math.min(newRect.top, viewHeight))
                newRect.right = Math.max(0f, Math.min(newRect.right, viewWidth))
                newRect.bottom = Math.max(0f, Math.min(newRect.bottom, viewHeight))
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
                            // Edge handles - move only that edge
                            1 -> { // top edge
                                newRect.top += deltaY
                            }
                            2 -> { // right edge
                                newRect.right += deltaX
                            }
                            3 -> { // bottom edge
                                newRect.bottom += deltaY
                            }
                            4 -> { // left edge
                                newRect.left += deltaX
                            }
                            // Corner handles - move adjacent edges
                            5 -> { // top-left
                                newRect.left += deltaX
                                newRect.top += deltaY
                            }
                            6 -> { // top-right
                                newRect.right += deltaX
                                newRect.top += deltaY
                            }
                            7 -> { // bottom-left
                                newRect.left += deltaX
                                newRect.bottom += deltaY
                            }
                            8 -> { // bottom-right
                                newRect.right += deltaX
                                newRect.bottom += deltaY
                            }
                            9 -> { // center - move entire rectangle
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
    }
}