package com.tamad.editss

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.tamad.editss.DrawMode
import android.graphics.RectF

class DrawingView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paint = Paint()
    private val currentPath = Path()
    private val cropPaint = Paint()
    private val cropCornerPaint = Paint()

    private var baseBitmap: Bitmap? = null
    private var paths = listOf<DrawingAction>()
    private val imageMatrix = android.graphics.Matrix()
    private val imageBounds = RectF()

    private var currentDrawMode = DrawMode.PEN
    private var currentTool: ToolType = ToolType.DRAW
    private var currentCropMode: CropMode = CropMode.FREEFORM
    private var startX = 0f
    private var startY = 0f

    private var isDrawing = false
    private var isCropping = false
    private var cropRect = RectF()
    private var isMovingCropRect = false
    private var isResizingCropRect = false
    private var resizeHandle: Int = 0 // 0=none, 1=top-left, 2=top-right, 3=bottom-left, 4=bottom-right

    // Gesture detection - REMOVED scale detector for crop mode
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var cropStartX = 0f
    private var cropStartY = 0f
    private var cropStartLeft = 0f
    private var cropStartTop = 0f
    private var cropStartRight = 0f
    private var cropStartBottom = 0f

    var onNewPath: ((DrawingAction) -> Unit)? = null
    var onCropApplied: ((Bitmap) -> Unit)? = null
    var onCropCanceled: (() -> Unit)? = null

    init {
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND

        cropPaint.isAntiAlias = true
        cropPaint.style = Paint.Style.STROKE
        cropPaint.color = Color.WHITE
        cropPaint.strokeWidth = 3f

        cropCornerPaint.isAntiAlias = true
        cropCornerPaint.style = Paint.Style.FILL
        cropCornerPaint.color = Color.WHITE
    }

    enum class ToolType {
        DRAW,
        CROP
    }

    fun setDrawingState(drawingState: DrawingState) {
        paint.color = drawingState.color
        paint.strokeWidth = drawingState.size
        paint.alpha = drawingState.opacity
        currentDrawMode = drawingState.drawMode
    }

    fun setBitmap(bitmap: Bitmap?) {
        baseBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        background = resources.getDrawable(R.drawable.outer_bounds, null)

        updateImageMatrix()
        invalidate()

        // Re-initialize crop rectangle after image is set
        post {
            if (currentTool == ToolType.CROP) {
                initializeDefaultCropRect()
            }
        }
    }

    fun setPaths(paths: List<DrawingAction>) {
        this.paths = paths
        invalidate()
    }

    fun setToolType(toolType: ToolType) {
        this.currentTool = toolType
        if (toolType == ToolType.CROP) {
            initializeDefaultCropRect()
        }
        invalidate()
    }

    fun setCropMode(cropMode: CropMode) {
        this.currentCropMode = cropMode
        if (currentTool == ToolType.CROP) {
            initializeDefaultCropRect()
        }
        invalidate()
    }

    private fun resetCropRect() {
        cropRect.setEmpty()
    }

    private fun initializeDefaultCropRect() {
        // Set default crop rectangle to fill 100% of the available image space
        if (imageBounds.width() > 0 && imageBounds.height() > 0) {
            // No padding - fill 100% of available image space
            val availableWidth = imageBounds.width()
            val availableHeight = imageBounds.height()

            // Calculate dimensions based on aspect ratio
            var width = 0f
            var height = 0f

            when (currentCropMode) {
                CropMode.FREEFORM -> {
                    // Fill 100% of available space
                    width = availableWidth
                    height = availableHeight
                }
                CropMode.SQUARE -> {
                    // 1:1 ratio - use the smaller dimension
                    val size = Math.min(availableWidth, availableHeight)
                    width = size
                    height = size
                }
                CropMode.PORTRAIT -> {
                    // 9:16 ratio (width:height) - taller image
                    val maxWidthByHeight = availableHeight * 9 / 16f
                    width = Math.min(availableWidth, maxWidthByHeight)
                    height = width * 16 / 9f
                }
                CropMode.LANDSCAPE -> {
                    // 16:9 ratio (width:height) - wider image
                    val maxHeightByWidth = availableWidth * 9 / 16f
                    height = Math.min(availableHeight, maxHeightByWidth)
                    width = height * 16 / 9f
                }
            }

            // Center the rectangle
            val centerX = (imageBounds.left + imageBounds.right) / 2
            val centerY = (imageBounds.top + imageBounds.bottom) / 2

            cropRect.left = centerX - width / 2
            cropRect.top = centerY - height / 2
            cropRect.right = centerX + width / 2
            cropRect.bottom = centerY + height / 2

            // Ensure it's within image bounds
            clampCropRectToImage()
        }
    }

    private fun clampCropRectToImage() {
        if (imageBounds.width() <= 0) return

        cropRect.left = cropRect.left.coerceIn(imageBounds.left, imageBounds.right)
        cropRect.top = cropRect.top.coerceIn(imageBounds.top, imageBounds.bottom)
        cropRect.right = cropRect.right.coerceIn(imageBounds.left, imageBounds.right)
        cropRect.bottom = cropRect.bottom.coerceIn(imageBounds.top, imageBounds.bottom)
    }

    fun applyCrop(): Bitmap? {
        if (baseBitmap == null || cropRect.isEmpty) return null

        // Map crop rectangle from screen coordinates to image coordinates
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)

        val imageCropRect = RectF()
        inverseMatrix.mapRect(imageCropRect, cropRect)

        // Clamp to image bounds
        val left = imageCropRect.left.coerceIn(0f, baseBitmap!!.width.toFloat())
        val top = imageCropRect.top.coerceIn(0f, baseBitmap!!.height.toFloat())
        val right = imageCropRect.right.coerceIn(0f, baseBitmap!!.width.toFloat())
        val bottom = imageCropRect.bottom.coerceIn(0f, baseBitmap!!.height.toFloat())

        if (right <= left || bottom <= top) return null

        val croppedBitmap = Bitmap.createBitmap(
            baseBitmap!!,
            left.toInt(),
            top.toInt(),
            (right - left).toInt(),
            (bottom - top).toInt()
        )

        baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        cropRect.setEmpty()

        // Update image matrix to center the new image (do NOT reset)
        updateImageMatrix()
        invalidate()

        return baseBitmap
    }

    fun cancelCrop() {
        cropRect.setEmpty()
        invalidate()
    }

    private fun updateImageBounds() {
        baseBitmap?.let {
            val bitmapWidth = it.width.toFloat()
            val bitmapHeight = it.height.toFloat()
            imageBounds.set(0f, 0f, bitmapWidth, bitmapHeight)
            imageMatrix.mapRect(imageBounds)
        }
    }

    fun getDrawing(): Bitmap? {
        if (baseBitmap == null) return null
        val resultBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        val inverseMatrix = android.graphics.Matrix()
        imageMatrix.invert(inverseMatrix)

        // Apply the inverse matrix to the canvas
        canvas.concat(inverseMatrix)

        for (action in paths) {
            // Draw the path directly, as the canvas is already transformed
            canvas.drawPath(action.path, action.paint)
        }

        return resultBitmap
    }

    fun getDrawingOnTransparent(): Bitmap? {
        if (baseBitmap == null) return null
        // Create a new transparent bitmap with the same dimensions as the base bitmap.
        val resultBitmap = Bitmap.createBitmap(baseBitmap!!.width, baseBitmap!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // The paths are in screen coordinates, but the final bitmap should be in the original image's
        // coordinate space. We use the same inverse matrix transformation as getDrawing().
        val inverseMatrix = android.graphics.Matrix()
        imageMatrix.invert(inverseMatrix)

        for (action in paths) {
            val transformedPath = Path()
            action.path.transform(inverseMatrix, transformedPath)
            canvas.drawPath(transformedPath, action.paint)
        }

        return resultBitmap
    }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateImageMatrix()
        }
    
         override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        baseBitmap?.let {
            canvas.save()
            canvas.clipRect(imageBounds)
            if (it.hasAlpha()) {
                val checker = CheckerDrawable()
                val rect = android.graphics.Rect()
                imageBounds.roundOut(rect)
                checker.bounds = rect
                checker.draw(canvas)
            }
            canvas.drawBitmap(it, imageMatrix, null)
        }

        for (action in paths) {
            canvas.drawPath(action.path, action.paint)
        }

        if (isDrawing) {
            canvas.drawPath(currentPath, paint)
        }

        // Draw crop overlay if in crop mode
        if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
            drawCropOverlay(canvas)
        }

        baseBitmap?.let {
            canvas.restore()
        }
    }

    private fun drawCropOverlay(canvas: Canvas) {
        // Draw the crop rectangle border
        canvas.drawRect(cropRect, cropPaint)

        // Draw corner indicators
        val cornerSize = 30f
        canvas.drawRect(cropRect.left, cropRect.top, cropRect.left + cornerSize, cropRect.top + cornerSize, cropCornerPaint)
        canvas.drawRect(cropRect.right - cornerSize, cropRect.top, cropRect.right, cropRect.top + cornerSize, cropCornerPaint)
        canvas.drawRect(cropRect.left, cropRect.bottom - cornerSize, cropRect.left + cornerSize, cropRect.bottom, cropCornerPaint)
        canvas.drawRect(cropRect.right - cornerSize, cropRect.bottom - cornerSize, cropRect.right, cropRect.bottom, cropCornerPaint)
    }

    private fun updateImageMatrix() {
        baseBitmap?.let {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val bitmapWidth = it.width.toFloat()
            val bitmapHeight = it.height.toFloat()

            val scale: Float
            var dx = 0f
            var dy = 0f

            if (bitmapWidth / viewWidth > bitmapHeight / viewHeight) {
                scale = viewWidth / bitmapWidth
                dy = (viewHeight - bitmapHeight * scale) * 0.5f
            } else {
                scale = viewHeight / bitmapHeight
                dx = (viewWidth - bitmapWidth * scale) * 0.5f
            }

            imageMatrix.setScale(scale, scale)
            imageMatrix.postTranslate(dx, dy)

            imageBounds.set(0f, 0f, bitmapWidth, bitmapHeight)
            imageMatrix.mapRect(imageBounds)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y

                if (currentTool == ToolType.DRAW) {
                    isDrawing = true
                    startX = x
                    startY = y
                    currentPath.reset()
                    if (currentDrawMode == DrawMode.PEN) {
                        currentPath.moveTo(x, y)
                    }
                } else if (currentTool == ToolType.CROP) {
                    // Check if touch is on a corner (for resizing) - only if rectangle exists
                    if (cropRect.width() > 0 && cropRect.height() > 0) {
                        resizeHandle = getResizeHandle(x, y)
                        if (resizeHandle > 0) {
                            isResizingCropRect = true
                            cropStartLeft = cropRect.left
                            cropStartTop = cropRect.top
                            cropStartRight = cropRect.right
                            cropStartBottom = cropRect.bottom
                            return true
                        }
                    }
                    // Check if touch is inside the crop rectangle (for moving)
                    if (cropRect.contains(x, y)) {
                        isMovingCropRect = true
                        cropStartX = x
                        cropStartY = y
                        cropStartLeft = cropRect.left
                        cropStartTop = cropRect.top
                        cropStartRight = cropRect.right
                        cropStartBottom = cropRect.bottom
                        return true
                    }
                    // Only create new crop rectangle if we're not already in crop mode
                    // or if the existing rectangle is empty
                    if (cropRect.width() <= 0 || cropRect.height() <= 0) {
                        isCropping = true
                        startX = x
                        startY = y
                        cropRect.left = x
                        cropRect.top = y
                        cropRect.right = x
                        cropRect.bottom = y
                    }
                    // If there's already a crop rectangle, ignore outside touches
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentTool == ToolType.DRAW) {
                    if (!isDrawing) return false
                    if (currentDrawMode == DrawMode.PEN) {
                        currentPath.lineTo(x, y)
                    } else {
                        currentPath.reset()
                        when (currentDrawMode) {
                            DrawMode.CIRCLE -> {
                                val radius = Math.sqrt(Math.pow((startX - x).toDouble(), 2.0) + Math.pow((startY - y).toDouble(), 2.0)).toFloat()
                                currentPath.addCircle(startX, startY, radius, Path.Direction.CW)
                            }
                            DrawMode.SQUARE -> {
                                currentPath.addRect(startX, startY, x, y, Path.Direction.CW)
                            }
                            else -> {}
                        }
                    }
                    invalidate()
                } else if (currentTool == ToolType.CROP) {
                    if (isResizingCropRect) {
                        resizeCropRect(x, y)
                        clampCropRectToImage()
                        invalidate()
                    } else if (isMovingCropRect) {
                        val dx = x - cropStartX
                        val dy = y - cropStartY
                        cropRect.left = cropStartLeft + dx
                        cropRect.top = cropStartTop + dy
                        cropRect.right = cropStartRight + dx
                        cropRect.bottom = cropStartBottom + dy
                        clampCropRectToImage()
                        invalidate()
                    } else if (isCropping) {
                        updateCropRect(x, y)
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (currentTool == ToolType.DRAW) {
                    if (!isDrawing) return false
                    isDrawing = false

                    val newPaint = Paint(paint)
                    val newPath = Path(currentPath)
                    onNewPath?.invoke(DrawingAction(newPath, newPaint))

                    currentPath.reset()
                    invalidate()
                } else if (currentTool == ToolType.CROP) {
                    isCropping = false
                    isMovingCropRect = false
                    isResizingCropRect = false
                    resizeHandle = 0
                }
                return true
            }
            else -> return false
        }
        return true
    }

    private fun getResizeHandle(x: Float, y: Float): Int {
        val cornerSize = 20f // Much smaller - only actual corner hits
        val left = cropRect.left
        val top = cropRect.top
        val right = cropRect.right
        val bottom = cropRect.bottom

        // Check top-left corner
        if (Math.abs(x - left) <= cornerSize && Math.abs(y - top) <= cornerSize) {
            return 1
        }
        // Check top-right corner
        if (Math.abs(x - right) <= cornerSize && Math.abs(y - top) <= cornerSize) {
            return 2
        }
        // Check bottom-left corner
        if (Math.abs(x - left) <= cornerSize && Math.abs(y - bottom) <= cornerSize) {
            return 3
        }
        // Check bottom-right corner
        if (Math.abs(x - right) <= cornerSize && Math.abs(y - bottom) <= cornerSize) {
            return 4
        }
        return 0
    }

    private fun resizeCropRect(x: Float, y: Float) {
        val minSize = 50f
        var newLeft = cropStartLeft
        var newTop = cropStartTop
        var newRight = cropStartRight
        var newBottom = cropStartBottom

        when (resizeHandle) {
            1 -> { // top-left
                newLeft = x.coerceAtMost(cropStartRight - minSize)
                newTop = y.coerceAtMost(cropStartBottom - minSize)
            }
            2 -> { // top-right
                newRight = x.coerceAtLeast(cropStartLeft + minSize)
                newTop = y.coerceAtMost(cropStartBottom - minSize)
            }
            3 -> { // bottom-left
                newLeft = x.coerceAtMost(cropStartRight - minSize)
                newBottom = y.coerceAtLeast(cropStartTop + minSize)
            }
            4 -> { // bottom-right
                newRight = x.coerceAtLeast(cropStartLeft + minSize)
                newBottom = y.coerceAtLeast(cropStartTop + minSize)
            }
        }

        // Maintain aspect ratio for fixed modes
        when (currentCropMode) {
            CropMode.SQUARE -> {
                // 1:1 ratio
                val width = newRight - newLeft
                val height = newBottom - newTop
                val size = Math.max(width, height)
                when (resizeHandle) {
                    1 -> { // top-left
                        newLeft = newRight - size
                        newTop = newBottom - size
                    }
                    2 -> { // top-right
                        newRight = newLeft + size
                        newTop = newBottom - size
                    }
                    3 -> { // bottom-left
                        newLeft = newRight - size
                        newBottom = newTop + size
                    }
                    4 -> { // bottom-right
                        newRight = newLeft + size
                        newBottom = newTop + size
                    }
                }
            }
            CropMode.PORTRAIT -> {
                // 9:16 ratio (width:height)
                var width = newRight - newLeft
                var height = newBottom - newTop
                var targetHeight = width * 16 / 9f
                when (resizeHandle) {
                    1, 2 -> { // top handles
                        newTop = newBottom - targetHeight
                    }
                    3, 4 -> { // bottom handles
                        newBottom = newTop + targetHeight
                    }
                }
            }
            CropMode.LANDSCAPE -> {
                // 16:9 ratio (width:height)
                var width = newRight - newLeft
                var height = newBottom - newTop
                var targetWidth = height * 16 / 9f
                when (resizeHandle) {
                    1, 3 -> { // left handles
                        newLeft = newRight - targetWidth
                    }
                    2, 4 -> { // right handles
                        newRight = newLeft + targetWidth
                    }
                }
            }
            else -> {} // FREEFORM - no aspect ratio constraint
        }

        // Clamp to image bounds after aspect ratio adjustment
        newLeft = newLeft.coerceIn(imageBounds.left, imageBounds.right)
        newTop = newTop.coerceIn(imageBounds.top, imageBounds.bottom)
        newRight = newRight.coerceIn(imageBounds.left, imageBounds.right)
        newBottom = newBottom.coerceIn(imageBounds.top, imageBounds.bottom)

        // Ensure minimum size is maintained
        val width = newRight - newLeft
        val height = newBottom - newTop
        if (width < minSize) {
            val delta = (minSize - width) / 2
            newLeft -= delta
            newRight += delta
        }
        if (height < minSize) {
            val delta = (minSize - height) / 2
            newTop -= delta
            newBottom += delta
        }

        cropRect.left = newLeft
        cropRect.top = newTop
        cropRect.right = newRight
        cropRect.bottom = newBottom
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