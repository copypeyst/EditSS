package com.tamad.editss

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.graphics.RectF
import com.tamad.editss.DrawMode
import com.tamad.editss.DrawingState
import com.tamad.editss.DrawingAction
import com.tamad.editss.CropMode
import com.tamad.editss.CropAction
import com.tamad.editss.EditAction
import kotlin.math.pow
import kotlin.math.sqrt

class CanvasView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paint = Paint()
    private val currentPath = Path()
    private val cropPaint = Paint()
    private val cropCornerPaint = Paint()

    private val imagePaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }

    private var baseBitmap: Bitmap? = null
    private var paths = listOf<DrawingAction>()
    private val imageMatrix = Matrix()
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

    // Unified transformation matrix for pan and zoom
    private val transformMatrix = Matrix()
    private var isMultiTouching = false

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val values = FloatArray(9)
            transformMatrix.getValues(values)
            val currentScale = values[Matrix.MSCALE_X]

            var scaleFactor = detector.scaleFactor
            if (currentScale * scaleFactor < 0.5f) {
                scaleFactor = 0.5f / currentScale
            } else if (currentScale * scaleFactor > 5f) {
                scaleFactor = 5f / currentScale
            }

            transformMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    }

    private val scaleGestureDetector = ScaleGestureDetector(context, scaleListener)

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var cropStartX = 0f
    private var cropStartY = 0f
    private var cropStartLeft = 0f
    private var cropStartTop = 0f
    private var cropStartRight = 0f
    private var cropStartBottom = 0f
    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f

    var onNewPath: ((DrawingAction) -> Unit)? = null
    var onCropApplied: ((Bitmap) -> Unit)? = null
    var onCropCanceled: (() -> Unit)? = null
    var onCropAction: ((CropAction) -> Unit)? = null // New callback for crop actions
    var onUndoAction: ((EditAction) -> Unit)? = null // Callback for undo operations
    var onRedoAction: ((EditAction) -> Unit)? = null // Callback for redo operations

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
        cropCornerPaint.alpha = 192 // 75% opacity
    }

    enum class ToolType {
        DRAW,
        CROP,
        ADJUST
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

        resetZoomAndPan()
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

    fun getBaseBitmap(): Bitmap? = baseBitmap

    fun handleUndo(actions: List<EditAction>) {
        val drawingActions = actions.filterIsInstance<EditAction.Drawing>().map { it.action }
        this.paths = drawingActions
        invalidate()
    }

    fun handleRedo(actions: List<EditAction>) {
        val drawingActions = actions.filterIsInstance<EditAction.Drawing>().map { it.action }
        this.paths = drawingActions
        invalidate()
    }

    fun handleCropUndo(cropAction: CropAction) {
        baseBitmap = cropAction.previousBitmap.copy(Bitmap.Config.ARGB_8888, true)
        updateImageMatrix()
        cropRect.setEmpty()
        invalidate()
    }

    fun handleCropRedo(cropAction: CropAction) {
        if (baseBitmap != null && !cropAction.cropRect.isEmpty) {
            val inverseMatrix = Matrix()
            imageMatrix.invert(inverseMatrix)

            val imageCropRect = RectF()
            inverseMatrix.mapRect(imageCropRect, cropAction.cropRect)

            val left = imageCropRect.left.coerceIn(0f, baseBitmap!!.width.toFloat())
            val top = imageCropRect.top.coerceIn(0f, baseBitmap!!.height.toFloat())
            val right = imageCropRect.right.coerceIn(0f, baseBitmap!!.width.toFloat())
            val bottom = imageCropRect.bottom.coerceIn(0f, baseBitmap!!.height.toFloat())

            if (right > left && bottom > top) {
                val croppedBitmap = Bitmap.createBitmap(
                    baseBitmap!!,
                    left.toInt(),
                    top.toInt(),
                    (right - left).toInt(),
                    (bottom - top).toInt()
                )

                baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                updateImageMatrix()
                cropRect.setEmpty()
                invalidate()
            }
        }
    }

    fun handleAdjustUndo(action: AdjustAction) {
        baseBitmap = action.previousBitmap.copy(Bitmap.Config.ARGB_8888, true)
        updateImageMatrix()
        invalidate()
    }

    fun handleAdjustRedo(action: AdjustAction) {
        baseBitmap = action.newBitmap.copy(Bitmap.Config.ARGB_8888, true)
        updateImageMatrix()
        invalidate()
    }

    fun processUndoAction(action: EditAction) {
        when (action) {
            is EditAction.Drawing -> {}
            is EditAction.Crop -> handleCropUndo(action.action)
            is EditAction.Adjust -> handleAdjustUndo(action.action)
        }
    }

    fun processRedoAction(action: EditAction) {
        when (action) {
            is EditAction.Drawing -> {}
            is EditAction.Crop -> handleCropRedo(action.action)
            is EditAction.Adjust -> handleAdjustRedo(action.action)
        }
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

    private fun resetZoomAndPan() {
        transformMatrix.reset()
    }

    private fun resetCropRect() {
        cropRect.setEmpty()
    }

    private fun initializeDefaultCropRect() {
        if (imageBounds.width() > 0 && imageBounds.height() > 0) {
            val availableWidth = imageBounds.width()
            val availableHeight = imageBounds.height()
            var width = 0f
            var height = 0f

            when (currentCropMode) {
                CropMode.FREEFORM -> {
                    width = availableWidth
                    height = availableHeight
                }
                CropMode.SQUARE -> {
                    val size = Math.min(availableWidth, availableHeight)
                    width = size
                    height = size
                }
                CropMode.PORTRAIT -> {
                    val maxWidthByHeight = availableHeight * 9 / 16f
                    width = Math.min(availableWidth, maxWidthByHeight)
                    height = width * 16 / 9f
                }
                CropMode.LANDSCAPE -> {
                    val maxHeightByWidth = availableWidth * 9 / 16f
                    height = Math.min(availableHeight, maxHeightByWidth)
                    width = height * 16 / 9f
                }
            }

            val centerX = (imageBounds.left + imageBounds.right) / 2
            val centerY = (imageBounds.top + imageBounds.bottom) / 2

            cropRect.left = centerX - width / 2
            cropRect.top = centerY - height / 2
            cropRect.right = centerX + width / 2
            cropRect.bottom = centerY + height / 2

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

        val previousBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true)

        val totalMatrix = Matrix(transformMatrix)
        totalMatrix.postConcat(imageMatrix)

        val inverseTotalMatrix = Matrix()
        totalMatrix.invert(inverseTotalMatrix)

        val imageCropRect = RectF(cropRect)
        inverseTotalMatrix.mapRect(imageCropRect)

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

        val cropAction = CropAction(
            previousBitmap = previousBitmap,
            cropRect = RectF(cropRect),
            cropMode = currentCropMode
        )
        onCropAction?.invoke(cropAction)

        cropRect.setEmpty()
        resetZoomAndPan()
        updateImageMatrix()
        invalidate()
        onCropApplied?.invoke(baseBitmap!!)

        return baseBitmap
    }

    fun cancelCrop() {
        cropRect.setEmpty()
        invalidate()
        onCropCanceled?.invoke()
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

        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        canvas.concat(inverseMatrix)

        for (action in paths) {
            canvas.drawPath(action.path, action.paint)
        }

        return resultBitmap
    }

    fun getDrawingOnTransparent(): Bitmap? {
        if (baseBitmap == null) return null
        val resultBitmap = Bitmap.createBitmap(baseBitmap!!.width, baseBitmap!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        val inverseMatrix = Matrix()
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
        canvas.save()
        canvas.concat(transformMatrix)

        baseBitmap?.let {
            canvas.save()
            canvas.clipRect(imageBounds)
            if (it.hasAlpha()) {
                val checker = CheckerDrawable()
                val rect = Rect()
                imageBounds.roundOut(rect)
                checker.bounds = rect
                checker.draw(canvas)
            }
            canvas.drawBitmap(it, imageMatrix, imagePaint)
            canvas.restore()
        }

        for (action in paths) {
            canvas.drawPath(action.path, action.paint)
        }

        if (isDrawing) {
            canvas.drawPath(currentPath, paint)
        }

        canvas.restore()

        if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
            drawCropOverlay(canvas)
        }
    }

    private fun drawCropOverlay(canvas: Canvas) {
        val overlayPaint = Paint().apply {
            color = Color.BLACK
            alpha = 128
        }
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, overlayPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, overlayPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, overlayPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), overlayPaint)
        canvas.drawRect(cropRect, cropPaint)

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

    private fun screenToCanvas(x: Float, y: Float): PointF {
        val point = floatArrayOf(x, y)
        val inverseMatrix = Matrix()
        transformMatrix.invert(inverseMatrix)
        inverseMatrix.mapPoints(point)
        return PointF(point[0], point[1])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        val x = event.x
        val y = event.y

        if (event.pointerCount > 1 || scaleGestureDetector.isInProgress) {
            if (!isMultiTouching) {
                isMultiTouching = true
                // Cancel any ongoing single-touch action
                isDrawing = false
                currentPath.reset()
                isCropping = false
                isMovingCropRect = false
                isResizingCropRect = false
                resizeHandle = 0
            }

            if (!scaleGestureDetector.isInProgress) {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                transformMatrix.postTranslate(dx, dy)
            }
            lastTouchX = x
            lastTouchY = y
            invalidate()
            return true
        }

        if (isMultiTouching && event.action == MotionEvent.ACTION_UP) {
            isMultiTouching = false
        }

        if (isMultiTouching) {
            lastTouchX = x
            lastTouchY = y
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y

                if (currentTool == ToolType.DRAW) {
                    val transformedPoint = screenToCanvas(x, y)
                    isDrawing = true
                    startX = transformedPoint.x
                    startY = transformedPoint.y
                    currentPath.reset()
                    if (currentDrawMode == DrawMode.PEN) {
                        currentPath.moveTo(transformedPoint.x, transformedPoint.y)
                    }
                } else if (currentTool == ToolType.CROP) {
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
                    if (!cropRect.isEmpty) {
                        return true
                    }
                    isCropping = true
                    startX = x
                    startY = y
                    cropRect.set(x, y, x, y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentTool == ToolType.DRAW) {
                    if (!isDrawing) return false
                    val transformedPoint = screenToCanvas(x, y)
                    val tx = transformedPoint.x
                    val ty = transformedPoint.y
                    if (currentDrawMode == DrawMode.PEN) {
                        currentPath.lineTo(tx, ty)
                    } else {
                        currentPath.reset()
                        when (currentDrawMode) {
                            DrawMode.CIRCLE -> {
                                val radius = sqrt((startX - tx).pow(2) + (startY - ty).pow(2))
                                currentPath.addCircle(startX, startY, radius, Path.Direction.CW)
                            }
                            DrawMode.SQUARE -> {
                                currentPath.addRect(startX, startY, tx, ty, Path.Direction.CW)
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
                        var dx = x - cropStartX
                        var dy = y - cropStartY

                        val newLeft = cropStartLeft + dx
                        val newTop = cropStartTop + dy
                        val newRight = cropStartRight + dx
                        val newBottom = cropStartBottom + dy

                        if (newLeft < imageBounds.left) dx = imageBounds.left - cropStartLeft
                        if (newTop < imageBounds.top) dy = imageBounds.top - cropStartTop
                        if (newRight > imageBounds.right) dx = imageBounds.right - cropStartRight
                        if (newBottom > imageBounds.bottom) dy = imageBounds.bottom - cropStartBottom

                        cropRect.left = cropStartLeft + dx
                        cropRect.top = cropStartTop + dy
                        cropRect.right = cropStartRight + dx
                        cropRect.bottom = cropStartBottom + dy
                        invalidate()
                    } else if (isCropping) {
                        updateCropRect(x, y)
                        clampCropRectToImage()
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (currentTool == ToolType.DRAW) {
                    if (!isDrawing) return false
                    isDrawing = false
                    onNewPath?.invoke(DrawingAction(Path(currentPath), Paint(paint)))
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
        val cornerSize = 60f
        val left = cropRect.left
        val top = cropRect.top
        val right = cropRect.right
        val bottom = cropRect.bottom

        if (RectF(left, top, left + cornerSize, top + cornerSize).contains(x, y)) return 1
        if (RectF(right - cornerSize, top, right, top + cornerSize).contains(x, y)) return 2
        if (RectF(left, bottom - cornerSize, left + cornerSize, bottom).contains(x, y)) return 3
        if (RectF(right - cornerSize, bottom - cornerSize, right, bottom).contains(x, y)) return 4
        return 0
    }

    private fun resizeCropRect(x: Float, y: Float) {
        val minSize = 50f
        var newLeft = cropRect.left
        var newTop = cropRect.top
        var newRight = cropRect.right
        var newBottom = cropRect.bottom

        // Update the corner being dragged
        when (resizeHandle) {
            1 -> { newLeft = x; newTop = y }
            2 -> { newRight = x; newTop = y }
            3 -> { newLeft = x; newBottom = y }
            4 -> { newRight = x; newBottom = y }
        }

        // Enforce aspect ratio if not FREEFORM
        if (currentCropMode != CropMode.FREEFORM) {
            val aspectRatio = when (currentCropMode) {
                CropMode.SQUARE -> 1.0f
                CropMode.PORTRAIT -> 9.0f / 16.0f
                CropMode.LANDSCAPE -> 16.0f / 9.0f
                else -> 1.0f // Should not be reached due to the outer if, but required for exhaustive when
            }

            val fixedX = if (resizeHandle == 1 || resizeHandle == 3) cropRect.right else cropRect.left
            val fixedY = if (resizeHandle == 1 || resizeHandle == 2) cropRect.bottom else cropRect.top

            var desiredWidth = kotlin.math.abs(x - fixedX)
            var desiredHeight = kotlin.math.abs(y - fixedY)

            if (desiredWidth / desiredHeight > aspectRatio) {
                desiredHeight = (desiredWidth / aspectRatio).toFloat()
            } else {
                desiredWidth = (desiredHeight * aspectRatio).toFloat()
            }

            when (resizeHandle) {
                1 -> { newLeft = fixedX - desiredWidth; newTop = fixedY - desiredHeight }
                2 -> { newRight = fixedX + desiredWidth; newTop = fixedY - desiredHeight }
                3 -> { newLeft = fixedX - desiredWidth; newBottom = fixedY + desiredHeight }
                4 -> { newRight = fixedX + desiredWidth; newBottom = fixedY + desiredHeight }
            }
        }

        // Enforce min size
        if (newRight - newLeft < minSize) {
            if (resizeHandle == 1 || resizeHandle == 3) newLeft = newRight - minSize else newRight = newLeft + minSize
        }
        if (newBottom - newTop < minSize) {
            if (resizeHandle == 1 || resizeHandle == 2) newTop = newBottom - minSize else newBottom = newTop + minSize
        }

        cropRect.set(newLeft, newTop, newRight, newBottom)
    }

    private fun updateCropRect(x: Float, y: Float) {
        when (currentCropMode) {
            CropMode.FREEFORM -> cropRect.right = x; cropRect.bottom = y
            CropMode.SQUARE -> {
                val size = kotlin.math.max(x - startX, y - startY)
                cropRect.right = startX + size
                cropRect.bottom = startY + size
            }
            CropMode.PORTRAIT -> {
                val width = x - startX
                cropRect.right = x
                cropRect.bottom = startY + width * 16 / 9
            }
            CropMode.LANDSCAPE -> {
                val height = y - startY
                cropRect.bottom = y
                cropRect.right = startX + height * 16 / 9
            }
        }
    }

    fun setAdjustments(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        updateColorFilter()
        invalidate()
    }

    private fun updateColorFilter() {
        val colorMatrix = ColorMatrix()
        colorMatrix.set(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        val saturationMatrix = ColorMatrix()
        saturationMatrix.setSaturation(saturation)
        colorMatrix.postConcat(saturationMatrix)
        imagePaint.colorFilter = ColorMatrixColorFilter(colorMatrix)
    }

    fun applyAdjustmentsToBitmap(): Bitmap? {
        if (baseBitmap == null) return null
        val adjustedBitmap = Bitmap.createBitmap(baseBitmap!!.width, baseBitmap!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(adjustedBitmap)
        val paint = Paint()
        paint.colorFilter = imagePaint.colorFilter
        canvas.drawBitmap(baseBitmap!!, 0f, 0f, paint)
        return adjustedBitmap
    }

    fun resetAdjustments() {
        setAdjustments(0f, 1f, 1f)
    }
}
