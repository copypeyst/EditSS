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

    // Gesture detection
    private lateinit var scaleDetector: ScaleGestureDetector
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isScaling = false
    private var cropStartX = 0f
    private var cropStartY = 0f

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

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (currentTool != ToolType.CROP) return false

                val scaleFactor = detector.scaleFactor
                val focusX = detector.focusX
                val focusY = detector.focusY

                imageMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY)
                updateImageBounds()
                invalidate()
                return true
            }
        })
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

        // Initialize default crop rectangle if in crop mode
        if (currentTool == ToolType.CROP) {
            initializeDefaultCropRect()
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
        // Set default crop rectangle to fit the image with some padding
        if (imageBounds.width() > 0 && imageBounds.height() > 0) {
            val padding = 40f // Padding from edges
            val maxWidth = imageBounds.width() - (padding * 2)
            val maxHeight = imageBounds.height() - (padding * 2)

            // Calculate dimensions based on aspect ratio
            val ratio = when (currentCropMode) {
                CropMode.SQUARE -> 1f
                CropMode.PORTRAIT -> 9f / 16f // Height/Width for portrait
                CropMode.LANDSCAPE -> 16f / 9f // Width/Height for landscape
                else -> maxWidth / maxHeight // Use available space for freeform
            }

            var width = maxWidth
            var height = width * ratio

            // If height exceeds available space, adjust based on height
            if (height > maxHeight) {
                height = maxHeight
                width = height / ratio
            }

            // Center the rectangle
            val centerX = (imageBounds.left + imageBounds.right) / 2
            val centerY = (imageBounds.top + imageBounds.bottom) / 2

            cropRect.left = centerX - width / 2
            cropRect.top = centerY - height / 2
            cropRect.right = centerX + width / 2
            cropRect.bottom = centerY + height / 2
        }
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

        // Reset matrix and update bitmap
        imageMatrix.reset()
        updateImageMatrix()

        baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        cropRect.setEmpty()
        invalidate()

        return baseBitmap
    }

    fun cancelCrop() {
        cropRect.setEmpty()
        imageMatrix.reset()
        updateImageMatrix()
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
            // Re-initialize crop rectangle if in crop mode and it's not empty
            if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
                initializeDefaultCropRect()
            }
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
        // Always let scale detector handle the event first for pinch gestures
        scaleDetector.onTouchEvent(event)

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
                    // Check if touch is inside the existing crop rectangle
                    if (cropRect.contains(x, y)) {
                        // Start moving the crop rectangle
                        isMovingCropRect = true
                        cropStartX = x
                        cropStartY = y
                    } else {
                        // Create new crop rectangle
                        isCropping = true
                        startX = x
                        startY = y
                        cropRect.left = x
                        cropRect.top = y
                        cropRect.right = x
                        cropRect.bottom = y
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Handle pan gestures in crop mode
                if (currentTool == ToolType.CROP && !isScaling) {
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY

                    // Check if we're moving the crop rectangle
                    if (isMovingCropRect) {
                        cropRect.offset(dx, dy)
                        // Keep crop rect within image bounds
                        if (cropRect.left < imageBounds.left) {
                            cropRect.offset(imageBounds.left - cropRect.left, 0f)
                        }
                        if (cropRect.right > imageBounds.right) {
                            cropRect.offset(imageBounds.right - cropRect.right, 0f)
                        }
                        if (cropRect.top < imageBounds.top) {
                            cropRect.offset(0f, imageBounds.top - cropRect.top)
                        }
                        if (cropRect.bottom > imageBounds.bottom) {
                            cropRect.offset(0f, imageBounds.bottom - cropRect.bottom)
                        }
                        invalidate()
                        lastTouchX = x
                        lastTouchY = y
                        return true
                    }

                    // Check if we're panning (image should move with finger)
                    if (imageBounds.contains(x, y) && !isMovingCropRect) {
                        imageMatrix.postTranslate(dx, dy)
                        updateImageBounds()
                        // Also move the crop rectangle with the image
                        cropRect.offset(dx, dy)
                        invalidate()
                        lastTouchX = x
                        lastTouchY = y
                        return true
                    }
                }

                // Handle drawing or crop rectangle creation
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
                    if (isCropping) {
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
                }
                isScaling = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isScaling = true
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                isScaling = false
                return true
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