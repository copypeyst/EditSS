package com.tamad.editss

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.hypot

class CanvasView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private fun createWhiteBackgroundDrawable(): android.graphics.drawable.Drawable {
        val paint = Paint().apply { color = Color.WHITE }
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    private data class HistoryStep(
        val beforeState: Bitmap,
        val afterState: Bitmap,
        val bounds: Rect,
        val isFullState: Boolean
    )

    private val history = mutableListOf<HistoryStep>()
    private var historyIndex = -1
    private var savedHistoryIndex = -1

    private val MAX_HISTORY_STEPS = 50
    
    interface OnUndoRedoStateChangedListener {
        fun onStateChanged(canUndo: Boolean, canRedo: Boolean)
    }
    var undoRedoListener: OnUndoRedoStateChangedListener? = null

    private val paint = Paint()
    private var currentDrawingTool: DrawingTool = PenTool()
    private val cropPaint = Paint()
    private val cropCornerPaint = Paint()

    private val overlayPaint = Paint().apply {
        color = Color.BLACK
        alpha = 128
    }
    private val overlayPath = Path()

    private val imagePaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }

    private val checkerDrawable = CheckerDrawable()

    private var baseBitmap: Bitmap? = null
    private val imageMatrix = android.graphics.Matrix()
    private val imageBounds = RectF()

    private var scaleFactor = 1.0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var translationX = 0f
    private var translationY = 0f
    private var isZooming = false
    private var isDrawing = false
    private var lastPointerCount = 1

    private var currentTool: ToolType = ToolType.DRAW
    private var currentCropMode: CropMode = CropMode.FREEFORM
    private var isCropModeActive = false
    private var isCropping = false
    private var cropRect = RectF()
    private var isMovingCropRect = false
    private var isResizingCropRect = false
    private var resizeHandle: Int = 0
    private var isSketchMode = false

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

    private var density = 1f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f

    var onCropApplied: ((Bitmap) -> Unit)? = null
    var onCropCanceled: (() -> Unit)? = null

    init {
        density = context.resources.displayMetrics.density

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
        cropCornerPaint.alpha = 192
    }

    enum class ToolType {
        DRAW,
        CROP,
        ADJUST
    }

    private fun addHistoryStep(step: HistoryStep) {
        if (historyIndex < history.size - 1) {
            val fromIndex = historyIndex + 1
            val toIndex = history.size
            val removed = history.subList(fromIndex, toIndex)
            removed.forEach {
                it.beforeState.recycle()
                it.afterState.recycle()
            }
            removed.clear()
        }

        history.add(step)
        historyIndex = history.size - 1

        if (savedHistoryIndex > historyIndex) {
            savedHistoryIndex = -1
        }
        
        while (history.size > MAX_HISTORY_STEPS) {
            val removedStep = history.removeAt(0)
            removedStep.beforeState.recycle()
            removedStep.afterState.recycle()
            historyIndex--
            if (savedHistoryIndex >= 0) {
                savedHistoryIndex--
            }
        }
        undoRedoListener?.onStateChanged(canUndo(), canRedo())
    }

    private fun applyPatch(patch: Bitmap, bounds: Rect) {
        baseBitmap?.let {
            if (!it.isRecycled) {
                val canvas = Canvas(it)
                val paint = Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                }
                canvas.drawBitmap(patch, bounds.left.toFloat(), bounds.top.toFloat(), paint)
            }
        }
    }

    fun undo() {
        if (!canUndo()) return

        val step = history[historyIndex]

        if (step.isFullState) {
            baseBitmap?.recycle()
            baseBitmap = step.beforeState.copy(step.beforeState.config ?: Bitmap.Config.ARGB_8888, true)
            updateImageMatrix()
        } else {
            applyPatch(step.beforeState, step.bounds)
        }
        
        historyIndex--
        invalidate()
        undoRedoListener?.onStateChanged(canUndo(), canRedo())
    }

    fun redo() {
        if (!canRedo()) return

        historyIndex++
        val step = history[historyIndex]
        
        if (step.isFullState) {
             baseBitmap?.recycle()
             baseBitmap = step.afterState.copy(step.afterState.config ?: Bitmap.Config.ARGB_8888, true)
             updateImageMatrix()
        } else {
            applyPatch(step.afterState, step.bounds)
        }

        invalidate()
        undoRedoListener?.onStateChanged(canUndo(), canRedo())
    }

    fun clearHistory() {
        history.forEach {
            it.beforeState.recycle()
            it.afterState.recycle()
        }
        history.clear()
        historyIndex = -1
        savedHistoryIndex = -1
        undoRedoListener?.onStateChanged(canUndo(), canRedo())
    }

    fun markAsSaved() {
        savedHistoryIndex = historyIndex
    }

    fun hasUnsavedChanges(): Boolean {
        return savedHistoryIndex != historyIndex
    }

    fun setBitmap(bitmap: Bitmap?) {
        clearHistory()
        baseBitmap?.recycle()
        baseBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        
        baseBitmap?.let {
            val initialBmp = it.copy(it.config ?: Bitmap.Config.ARGB_8888, false)
            val bounds = Rect(0, 0, it.width, it.height)
            val afterBmp = initialBmp.copy(it.config ?: Bitmap.Config.ARGB_8888, false)
            addHistoryStep(HistoryStep(initialBmp, afterBmp, bounds, true))
            savedHistoryIndex = 0
            historyIndex = 0
        }

        background = ContextCompat.getDrawable(context, R.drawable.outer_bounds)
        updateImageMatrix()
        invalidate()
        undoRedoListener?.onStateChanged(canUndo(), canRedo())
        
        post {
            if (currentTool == ToolType.CROP && isCropModeActive) {
                setCropMode(currentCropMode)
            }
        }
    }

    fun canUndo(): Boolean = historyIndex > 0
    fun canRedo(): Boolean = historyIndex < history.size - 1

    private fun commitDrawingStroke(action: DrawingAction) {
        val currentBitmap = baseBitmap ?: return
        if (currentBitmap.isRecycled) return

        val strokeBoundsF = RectF()
        action.path.computeBounds(strokeBoundsF, true)
        
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        inverseMatrix.mapRect(strokeBoundsF)

        val dirtyRect = Rect()
        strokeBoundsF.roundOut(dirtyRect)
        
        val strokeWidth = action.paint.strokeWidth.toInt()
        val scalePadding = (strokeWidth * scaleFactor * 1.5f).toInt()
        val antiAliasPadding = (strokeWidth * 0.5f).toInt()
        val basePadding = 8
        val totalPadding = strokeWidth + scalePadding + antiAliasPadding + basePadding
        
        dirtyRect.inset(-totalPadding, -totalPadding)

        if (!dirtyRect.intersect(0, 0, currentBitmap.width, currentBitmap.height)) {
            return
        }

        val beforePatch = try {
            Bitmap.createBitmap(currentBitmap, dirtyRect.left, dirtyRect.top, dirtyRect.width(), dirtyRect.height())
        } catch (e: Exception) { return }

        mergeDrawingStrokeIntoBitmap(action)

        val afterPatch = try {
            Bitmap.createBitmap(currentBitmap, dirtyRect.left, dirtyRect.top, dirtyRect.width(), dirtyRect.height())
        } catch (e: Exception) { 
            beforePatch.recycle()
            applyPatch(beforePatch, dirtyRect)
            return 
        }

        addHistoryStep(HistoryStep(beforePatch, afterPatch, dirtyRect, false))
    }
    
    fun applyCrop(): Bitmap? {
        if (baseBitmap == null || baseBitmap!!.isRecycled || cropRect.isEmpty) return null

        val beforeState = baseBitmap!!.copy(baseBitmap!!.config ?: Bitmap.Config.ARGB_8888, true)
        val bitmapWithDrawings = beforeState

        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        val imageCropRect = RectF()
        inverseMatrix.mapRect(imageCropRect, cropRect)

        val left = imageCropRect.left.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val top = imageCropRect.top.coerceIn(0f, bitmapWithDrawings.height.toFloat())
        val right = imageCropRect.right.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val bottom = imageCropRect.bottom.coerceIn(0f, bitmapWithDrawings.height.toFloat())

        if (right <= left || bottom <= top) {
            beforeState.recycle()
            return null
        }

        if (right - left < 50 || bottom - top < 50) {
            beforeState.recycle()
            return null
        }

        try {
            val croppedBitmap = Bitmap.createBitmap(
                bitmapWithDrawings,
                left.toInt(),
                top.toInt(),
                (right - left).toInt(),
                (bottom - top).toInt()
            )

            baseBitmap?.recycle()
            baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
            
            val afterState = baseBitmap!!.copy(baseBitmap!!.config ?: Bitmap.Config.ARGB_8888, true)
            addHistoryStep(HistoryStep(beforeState, afterState, Rect(0, 0, afterState.width, afterState.height), true))

            cropRect.setEmpty()
            scaleFactor = 1.0f
            translationX = 0f
            translationY = 0f
            updateImageMatrix()
            invalidate()
            onCropApplied?.invoke(baseBitmap!!)

            return baseBitmap
        } catch (e: OutOfMemoryError) {
            beforeState.recycle()
            e.printStackTrace()
            return null
        }
    }

    fun applyAdjustmentsToBitmap(): Bitmap? {
        if (baseBitmap == null || baseBitmap!!.isRecycled) return null

        val beforeState = baseBitmap!!.copy(baseBitmap!!.config ?: Bitmap.Config.ARGB_8888, true)

        val adjustedBitmap = Bitmap.createBitmap(baseBitmap!!.width, baseBitmap!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(adjustedBitmap)
        val paint = Paint().apply { colorFilter = imagePaint.colorFilter }
        canvas.drawBitmap(baseBitmap!!, 0f, 0f, paint)
        
        baseBitmap?.recycle()
        baseBitmap = adjustedBitmap
        
        val afterState = baseBitmap!!.copy(baseBitmap!!.config ?: Bitmap.Config.ARGB_8888, true)
        addHistoryStep(HistoryStep(beforeState, afterState, Rect(0, 0, afterState.width, afterState.height), true))
        
        invalidate()
        return baseBitmap
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearHistory()
        baseBitmap = null
    }
    
    fun setDrawingState(drawingState: DrawingState) {
        paint.color = drawingState.color
        paint.strokeWidth = drawingState.size
        paint.alpha = drawingState.opacity
        currentDrawingTool = when (drawingState.drawMode) {
            DrawMode.PEN -> PenTool()
            DrawMode.CIRCLE -> CircleTool()
            DrawMode.SQUARE -> SquareTool()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        baseBitmap?.let {
            if (!it.isRecycled) {
                canvas.save()
                canvas.clipRect(imageBounds)

                if (isSketchMode) {
                    canvas.drawColor(Color.WHITE)
                } else if (it.hasAlpha()) {
                    checkerDrawable.draw(canvas)
                }
                
                canvas.drawBitmap(it, imageMatrix, imagePaint)
                canvas.restore()
            }
        }
        
        if (isDrawing) {
            currentDrawingTool.onDraw(canvas, paint)
        }

        if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
            drawCropOverlay(canvas)
        }
    }

    fun setSketchMode(isSketch: Boolean) {
        this.isSketchMode = isSketch
        
        background = ContextCompat.getDrawable(context, R.drawable.outer_bounds)
        
        invalidate()
    }

    fun getBaseBitmap(): Bitmap? = if (baseBitmap?.isRecycled == false) baseBitmap else null

    fun setToolType(toolType: ToolType) {
        this.currentTool = toolType
        if (toolType == ToolType.CROP) {
            if (isCropModeActive) {
                initializeDefaultCropRect()
            }
        } else {
            isCropModeActive = false
        }
        invalidate()
    }

    fun setCropMode(cropMode: CropMode) {
        this.currentCropMode = cropMode
        this.isCropModeActive = true
        cropRect.setEmpty()
        if (currentTool == ToolType.CROP) {
            initializeDefaultCropRect()
        }
        invalidate()
    }

    fun setCropModeInactive() {
        this.isCropModeActive = false
        if (currentTool == ToolType.CROP) {
            cropRect.setEmpty()
        }
        invalidate()
    }

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (this@CanvasView.lastPointerCount < 2) return false

            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f)

            val inverseMatrix = Matrix()
            imageMatrix.invert(inverseMatrix)
            val focusPoint = floatArrayOf(detector.focusX, detector.focusY)
            val imagePoint = floatArrayOf(0f, 0f)
            inverseMatrix.mapPoints(imagePoint, focusPoint)

            updateImageMatrix()

            val screenPoint = floatArrayOf(0f, 0f)
            imageMatrix.mapPoints(screenPoint, imagePoint)

            translationX += detector.focusX - screenPoint[0]
            translationY += detector.focusY - screenPoint[1]

            updateImageMatrix()
            invalidate()
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (this@CanvasView.lastPointerCount < 2) return false
            isZooming = true
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isZooming = false
            if (scaleFactor <= 1.0f) {
                scaleFactor = 1.0f
                translationX = 0f
                translationY = 0f
                updateImageMatrix()
                invalidate()
            }
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastPointerCount = event.pointerCount
        scaleGestureDetector.onTouchEvent(event)

        if (handleMultiTouchGesture(event)) {
            return true
        }

        val x = event.x
        val y = event.y

        return when (currentTool) {
            ToolType.DRAW -> handleDrawTouchEvent(event)
            ToolType.CROP -> handleCropTouchEvent(event, x, y)
            else -> false
        }
    }

    private fun handleMultiTouchGesture(event: MotionEvent): Boolean {
        if (!isZooming && event.pointerCount <= 1) return false

        if (isDrawing && currentTool == ToolType.DRAW) {
            val cancelEvent = MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_CANCEL, event.x, event.y, 0)
            currentDrawingTool.onTouchEvent(cancelEvent, paint)
            cancelEvent.recycle()
            isDrawing = false
            invalidate()
        }

        if (currentTool == ToolType.CROP && (isMovingCropRect || isResizingCropRect)) {
            isMovingCropRect = false
            isResizingCropRect = false
            resizeHandle = 0
            invalidate()
        }

        if (scaleFactor > 1.0f) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) {
                        handlePanning(event)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    lastFocusX = 0f
                    lastFocusY = 0f
                }
            }
        }
        return true
    }

    private fun handlePanning(event: MotionEvent) {
        val focusX = (event.getX(0) + event.getX(1)) / 2
        val focusY = (event.getY(0) + event.getY(1)) / 2
        if (lastFocusX != 0f || lastFocusY != 0f) {
            translationX += focusX - lastFocusX
            translationY += focusY - lastFocusY
        }
        lastFocusX = focusX
        lastFocusY = focusY
        updateImageMatrix()
        invalidate()
    }

    private fun handleDrawTouchEvent(event: MotionEvent): Boolean {
        val screenSpaceAction = currentDrawingTool.onTouchEvent(event, paint)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> isDrawing = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) {
                    isDrawing = false
                    screenSpaceAction?.let { commitDrawingStroke(it) }
                }
            }
        }
        
        invalidate()
        return true
    }
    
    private fun mergeDrawingStrokeIntoBitmap(action: DrawingAction) {
        if (baseBitmap == null || baseBitmap!!.isRecycled) return

        val canvas = Canvas(baseBitmap!!)
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        canvas.concat(inverseMatrix)
        canvas.drawPath(action.path, action.paint)
    }

    private fun handleCropTouchEvent(event: MotionEvent, x: Float, y: Float): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> handleCropTouchDown(x, y)
            MotionEvent.ACTION_MOVE -> handleCropTouchMove(x, y)
            MotionEvent.ACTION_UP -> handleCropTouchUp(x, y)
            else -> false
        }
    }

    private fun handleCropTouchDown(x: Float, y: Float): Boolean {
        lastTouchX = x
        lastTouchY = y

        if (!cropRect.isEmpty) {
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
            validateAndCorrectCropRect()
            isMovingCropRect = true
            touchOffsetX = cropRect.left - x
            touchOffsetY = cropRect.top - y
            return true
        }

        if (cropRect.isEmpty && isCropModeActive) {
            isCropping = true
            cropRect.set(x, y, x, y)
            return true
        }
        return true
    }

    private fun handleCropTouchMove(x: Float, y: Float): Boolean {
        if (isResizingCropRect) {
            resizeCropRect(x, y)
            clampCropRectToBounds()
            invalidate()
        } else if (isMovingCropRect) {
            moveCropRect(x, y)
        } else if (isCropping) {
            updateCropRect(x, y)
            clampCropRectToBounds()
            invalidate()
        }
        return true
    }

    private fun handleCropTouchUp(x: Float, y: Float): Boolean {
        if (isCropping) {
            enforceAspectRatio()
            updateCropRect(x, y)
        }
        isCropping = false
        isMovingCropRect = false
        isResizingCropRect = false
        resizeHandle = 0
        return true
    }

    private fun initializeDefaultCropRect() {
        if (imageBounds.width() > 0 && imageBounds.height() > 0) {
            val visibleBounds = getVisibleImageBounds()
            if (visibleBounds.width() > 0 && visibleBounds.height() > 0) {
                var width: Float
                var height: Float
                when (currentCropMode) {
                    CropMode.FREEFORM -> {
                        width = visibleBounds.width()
                        height = visibleBounds.height()
                    }
                    CropMode.SQUARE -> {
                        val size = Math.min(visibleBounds.width(), visibleBounds.height())
                        width = size
                        height = size
                    }
                    CropMode.PORTRAIT -> {
                        height = visibleBounds.height()
                        width = height * 9 / 16f
                        if (width > visibleBounds.width()) {
                            width = visibleBounds.width()
                            height = width * 16 / 9f
                        }
                    }
                    CropMode.LANDSCAPE -> {
                        width = visibleBounds.width()
                        height = width * 9 / 16f
                        if (height > visibleBounds.height()) {
                            height = visibleBounds.height()
                            width = height * 16 / 9f
                        }
                    }
                }
                val centerX = visibleBounds.centerX()
                val centerY = visibleBounds.centerY()
                cropRect.set(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
                clampCropRectToBounds()
            }
        }
    }

    private fun getVisibleImageBounds(): RectF {
        val visibleLeft = Math.max(imageBounds.left, 0f)
        val visibleTop = Math.max(imageBounds.top, 0f)
        val visibleRight = Math.min(imageBounds.right, width.toFloat())
        val visibleBottom = Math.min(imageBounds.bottom, height.toFloat())
        return RectF(visibleLeft, visibleTop, visibleRight, visibleBottom)
    }

    private fun clampCropRectToBounds() {
        if (imageBounds.width() <= 0) return
        val visibleBounds = getVisibleImageBounds()
        cropRect.left = cropRect.left.coerceIn(visibleBounds.left, visibleBounds.right)
        cropRect.top = cropRect.top.coerceIn(visibleBounds.top, visibleBounds.bottom)
        cropRect.right = cropRect.right.coerceIn(visibleBounds.left, visibleBounds.right)
        cropRect.bottom = cropRect.bottom.coerceIn(visibleBounds.top, visibleBounds.bottom)
    }

    fun cancelCrop() {
        cropRect.setEmpty()
        invalidate()
        onCropCanceled?.invoke()
    }

    private fun moveCropRect(x: Float, y: Float) {
        val newLeft = x + touchOffsetX
        val newTop = y + touchOffsetY
        val width = cropRect.width()
        val height = cropRect.height()

        var left = newLeft
        var top = newTop
        var right = left + width
        var bottom = top + height

        val visibleBounds = getVisibleImageBounds()

        if (left < visibleBounds.left) {
            left = visibleBounds.left
            right = left + width
        }
        if (top < visibleBounds.top) {
            top = visibleBounds.top
            bottom = top + height
        }
        if (right > visibleBounds.right) {
            right = visibleBounds.right
            left = right - width
        }
        if (bottom > visibleBounds.bottom) {
            bottom = visibleBounds.bottom
            top = bottom - height
        }

        cropRect.set(left, top, right, bottom)
        clampCropRectToBounds()
        invalidate()
    }

    private fun enforceAspectRatio() {
        if (currentCropMode == CropMode.FREEFORM || cropRect.isEmpty) return

        val targetAspectRatio = when (currentCropMode) {
            CropMode.SQUARE -> 1f
            CropMode.PORTRAIT -> 9f / 16f
            CropMode.LANDSCAPE -> 16f / 9f
            else -> return
        }

        val visibleBounds = getVisibleImageBounds()
        val width = cropRect.width()
        var height = width / targetAspectRatio

        if (cropRect.top + height > visibleBounds.bottom) {
            height = visibleBounds.bottom - cropRect.top
        }
        cropRect.bottom = cropRect.top + height
        invalidate()
    }

    private fun validateAndCorrectCropRect() {
        if (cropRect.isEmpty) return

        val targetAspectRatio: Float? = when (currentCropMode) {
            CropMode.SQUARE -> 1f
            CropMode.PORTRAIT -> 9f / 16f
            CropMode.LANDSCAPE -> 16f / 9f
            else -> null
        }

        val visibleBounds = getVisibleImageBounds()
        if (visibleBounds.width() <= 0 || visibleBounds.height() <= 0) return

        val centerX = cropRect.centerX().coerceIn(visibleBounds.left, visibleBounds.right)
        val centerY = cropRect.centerY().coerceIn(visibleBounds.top, visibleBounds.bottom)

        var width = cropRect.width()
        var height = cropRect.height()

        targetAspectRatio?.let { ratio ->
            if (width / height > ratio) {
                width = height * ratio
            } else {
                height = width / ratio
            }
        }

        val maxAllowedWidth = 2 * kotlin.math.min(centerX - visibleBounds.left, visibleBounds.right - centerX)
        val maxAllowedHeight = 2 * kotlin.math.min(centerY - visibleBounds.top, visibleBounds.bottom - centerY)

        if (width > maxAllowedWidth || height > maxAllowedHeight) {
            val widthScale = maxAllowedWidth / width
            val heightScale = maxAllowedHeight / height
            val scale = kotlin.math.min(widthScale, heightScale)
            width *= scale
            height *= scale
        }

        cropRect.set(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
        invalidate()
    }

    private fun getResizeHandle(x: Float, y: Float): Int {
        val hitRadius = 48f * density

        if (hypot(x - cropRect.left, y - cropRect.top) <= hitRadius) return 1
        if (hypot(x - cropRect.right, y - cropRect.top) <= hitRadius) return 2
        if (hypot(x - cropRect.left, y - cropRect.bottom) <= hitRadius) return 3
        if (hypot(x - cropRect.right, y - cropRect.bottom) <= hitRadius) return 4

        return 0
    }

    private fun resizeCropRect(x: Float, y: Float) {
        val targetX = x
        val targetY = y

        val aspectRatio = getAspectRatio()
        if (aspectRatio != null) {
            resizeCropRectWithAspectRatio(targetX, targetY, aspectRatio)
        } else {
            resizeCropRectFreeform(targetX, targetY)
        }
    }

    private fun getAspectRatio(): Float? {
        return when (currentCropMode) {
            CropMode.SQUARE -> 1f
            CropMode.PORTRAIT -> 9f / 16f
            CropMode.LANDSCAPE -> 16f / 9f
            else -> null
        }
    }

    private fun resizeCropRectWithAspectRatio(x: Float, y: Float, aspectRatio: Float) {
        val (fixedX, fixedY) = getFixedCorner()
        val (newWidth, newHeight) = calculateAspectRatioSize(x, y, fixedX, fixedY, aspectRatio)
        val constrainedSize = applySizeConstraints(newWidth, newHeight, fixedX, fixedY, aspectRatio)

        applyCropRectResize(constrainedSize.first, constrainedSize.second, fixedX, fixedY)
    }

    private fun getFixedCorner(): Pair<Float, Float> {
        return when (resizeHandle) {
            1 -> Pair(cropStartRight, cropStartBottom)
            2 -> Pair(cropStartLeft, cropStartBottom)
            3 -> Pair(cropStartRight, cropStartTop)
            4 -> Pair(cropStartLeft, cropStartTop)
            else -> Pair(cropStartLeft, cropStartTop)
        }
    }

    private fun calculateAspectRatioSize(x: Float, y: Float, fixedX: Float, fixedY: Float, aspectRatio: Float): Pair<Float, Float> {
        var newWidth = Math.abs(x - fixedX)
        var newHeight = Math.abs(y - fixedY)

        if (newWidth / newHeight > aspectRatio) {
            newWidth = newHeight * aspectRatio
        } else {
            newHeight = newWidth / aspectRatio
        }

        return Pair(newWidth, newHeight)
    }

    private fun applySizeConstraints(newWidth: Float, newHeight: Float, fixedX: Float, fixedY: Float, aspectRatio: Float): Pair<Float, Float> {
        val minCropSize = 50f
        val minHeight = if (aspectRatio > 1) minCropSize else minCropSize / aspectRatio
        val minWidth = if (aspectRatio < 1) minCropSize else minCropSize * aspectRatio

        var constrainedWidth = newWidth.coerceAtLeast(minWidth)
        var constrainedHeight = newHeight.coerceAtLeast(minHeight)

        val visibleBounds = getVisibleImageBounds()
        val maxAllowedWidth = when (resizeHandle) {
            1, 3 -> fixedX - visibleBounds.left
            else -> visibleBounds.right - fixedX
        }
        val maxAllowedHeight = when (resizeHandle) {
            1, 2 -> fixedY - visibleBounds.top
            else -> visibleBounds.bottom - fixedY
        }

        if (constrainedWidth > maxAllowedWidth || constrainedHeight > maxAllowedHeight) {
            val widthScale = maxAllowedWidth / constrainedWidth
            val heightScale = maxAllowedHeight / constrainedHeight
            val scale = kotlin.math.min(widthScale, heightScale)
            constrainedWidth *= scale
            constrainedHeight *= scale
        }

        return Pair(constrainedWidth, constrainedHeight)
    }

    private fun applyCropRectResize(width: Float, height: Float, fixedX: Float, fixedY: Float) {
        when (resizeHandle) {
            1 -> cropRect.set(fixedX - width, fixedY - height, fixedX, fixedY)
            2 -> cropRect.set(fixedX, fixedY - height, fixedX + width, fixedY)
            3 -> cropRect.set(fixedX - width, fixedY, fixedX, fixedY + height)
            4 -> cropRect.set(fixedX, fixedY, fixedX + width, fixedY + height)
        }
    }

    private fun resizeCropRectFreeform(x: Float, y: Float) {
        val minCropSize = 50f
        when (resizeHandle) {
            1 -> {
                cropRect.left = x.coerceAtMost(cropStartRight - minCropSize)
                cropRect.top = y.coerceAtMost(cropStartBottom - minCropSize)
            }
            2 -> {
                cropRect.right = x.coerceAtLeast(cropStartLeft + minCropSize)
                cropRect.top = y.coerceAtMost(cropStartBottom - minCropSize)
            }
            3 -> {
                cropRect.left = x.coerceAtMost(cropStartRight - minCropSize)
                cropRect.bottom = y.coerceAtLeast(cropStartTop + minCropSize)
            }
            4 -> {
                cropRect.right = x.coerceAtLeast(cropStartLeft + minCropSize)
                cropRect.bottom = y.coerceAtLeast(cropStartTop + minCropSize)
            }
        }
    }

    private fun updateCropRect(x: Float, y: Float) {
        cropRect.right = Math.max(x, cropRect.left)
        cropRect.bottom = Math.max(y, cropRect.top)
        val startX = cropRect.left
        val startY = cropRect.top

        val targetAspectRatio: Float? = when (currentCropMode) {
            CropMode.SQUARE -> 1f
            CropMode.PORTRAIT -> 9f / 16f
            CropMode.LANDSCAPE -> 16f / 9f
            else -> null
        }

        if (targetAspectRatio != null) {
            var newWidth = x - startX
            var newHeight = y - startY

            if (newWidth / newHeight > targetAspectRatio) {
                newHeight = newWidth / targetAspectRatio
            } else {
                newWidth = newHeight * targetAspectRatio
            }
            cropRect.right = startX + newWidth
            cropRect.bottom = startY + newHeight
        } else {
            cropRect.right = x
            cropRect.bottom = y
        }
    }

    fun setAdjustments(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        updateColorFilter()
        invalidate()
    }

    fun clearAdjustments() {
        this.brightness = 0f
        this.contrast = 1f
        this.saturation = 1f
        imagePaint.colorFilter = null
        invalidate()
    }

    private fun updateColorFilter() {
        val colorMatrix = ColorMatrix()
        val translation = brightness + (1f - contrast) * 128f
        colorMatrix.set(floatArrayOf(
            contrast, 0f, 0f, 0f, translation,
            0f, contrast, 0f, 0f, translation,
            0f, 0f, contrast, 0f, translation,
            0f, 0f, 0f, 1f, 0f
        ))

        val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
        colorMatrix.postConcat(saturationMatrix)
        imagePaint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        imagePaint.xfermode = null
    }

    fun resetAdjustments() {
        setAdjustments(0f, 1f, 1f)
    }

    fun getDrawing(): Bitmap? {
        if (baseBitmap == null || baseBitmap!!.isRecycled) return null
        return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun getDrawingOnTransparent(): Bitmap? {
        if (baseBitmap == null || baseBitmap!!.isRecycled) return null
        return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun getTransparentDrawing(): Bitmap? {
        return getDrawingOnTransparent()
    }

    fun getSketchDrawingOnWhite(): Bitmap? {
        val drawingBitmap = getFinalBitmap() ?: return null
        val whiteBitmap = Bitmap.createBitmap(drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whiteBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(drawingBitmap, 0f, 0f, null)
        drawingBitmap.recycle()
        return whiteBitmap
    }

    fun getFinalBitmap(): Bitmap? {
        if (baseBitmap == null || baseBitmap!!.isRecycled) return null

        val hasAdjustments = brightness != 0f || contrast != 1f || saturation != 1f
        if (!hasAdjustments) {
            return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
        }

        val adjustedBitmap = Bitmap.createBitmap(baseBitmap!!.width, baseBitmap!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(adjustedBitmap)

        val paint = Paint().apply {
            colorFilter = imagePaint.colorFilter
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        canvas.drawBitmap(baseBitmap!!, 0f, 0f, paint)
        return adjustedBitmap
    }

    fun convertTransparentToWhite(bitmap: Bitmap): Bitmap {
        val whiteBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whiteBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return whiteBitmap
    }

    fun getTransparentDrawingWithAdjustments(): Bitmap? {
        return getFinalBitmap()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageMatrix()
    }

    private fun drawCropOverlay(canvas: Canvas) {
        overlayPath.reset()
        overlayPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        overlayPath.addRect(cropRect, Path.Direction.CCW)
        canvas.drawPath(overlayPath, overlayPaint)
        canvas.drawRect(cropRect, cropPaint)
        val cornerSize = 30f
        canvas.drawRect(cropRect.left, cropRect.top, cropRect.left + cornerSize, cropRect.top + cornerSize, cropCornerPaint)
        canvas.drawRect(cropRect.right - cornerSize, cropRect.top, cropRect.right, cropRect.top + cornerSize, cropCornerPaint)
        canvas.drawRect(cropRect.left, cropRect.bottom - cornerSize, cropRect.left + cornerSize, cropRect.bottom, cropCornerPaint)
        canvas.drawRect(cropRect.right - cornerSize, cropRect.bottom - cornerSize, cropRect.right, cropRect.bottom, cropCornerPaint)
    }

    private fun updateImageMatrix() {
        baseBitmap?.let {
            if (it.isRecycled) return
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val bitmapWidth = it.width.toFloat()
            val bitmapHeight = it.height.toFloat()
            val baseScale = if (bitmapWidth / viewWidth > bitmapHeight / viewHeight) {
                viewWidth / bitmapWidth
            } else {
                viewHeight / bitmapHeight
            }
            val baseDx = (viewWidth - bitmapWidth * baseScale) / 2f
            val baseDy = (viewHeight - bitmapHeight * baseScale) / 2f
            imageMatrix.setScale(baseScale * scaleFactor, baseScale * scaleFactor)
            imageMatrix.postTranslate(baseDx + translationX, baseDy + translationY)
            imageBounds.set(0f, 0f, bitmapWidth, bitmapHeight)
            imageMatrix.mapRect(imageBounds)

            imageBounds.roundOut(checkerDrawable.bounds)
        }
    }
}