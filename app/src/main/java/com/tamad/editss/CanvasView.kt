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
import com.tamad.editss.CropMode
import com.tamad.editss.CropAction
import com.tamad.editss.EditAction
import com.tamad.editss.DrawingAction

class CanvasView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paint = Paint()
    private var currentDrawingTool: DrawingTool = PenTool()
    private val cropPaint = Paint()
    private val cropCornerPaint = Paint()

    private val imagePaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }

    private var baseBitmap: Bitmap? = null
    private val imageMatrix = android.graphics.Matrix()
    private val imageBounds = RectF()
    
    private val bitmapHistory = mutableListOf<Bitmap>()
    private var currentHistoryIndex = -1

    private var scaleFactor = 1.0f
    private var lastFocusX = 0f // For multi-touch panning
    private var lastFocusY = 0f // For multi-touch panning
    private var translationX = 0f
    private var translationY = 0f
    private var isZooming = false
    private var isDrawing = false
    private var lastPointerCount = 1 // Track pointer count for scale detection

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (this@CanvasView.lastPointerCount < 2) {
                return false
            }
            
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f) // Limit zoom out to 1.0x and zoom in to 5.0x

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
            if (this@CanvasView.lastPointerCount < 2) {
                return false
            }
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


    private var currentTool: ToolType = ToolType.DRAW
    private var currentCropMode: CropMode = CropMode.FREEFORM

    private var isCropModeActive = false // Track if crop mode is actually selected
    private var isCropping = false
    private var cropRect = RectF()
    private var isMovingCropRect = false
    private var isResizingCropRect = false
    private var resizeHandle: Int = 0 // 0=none, 1=top-left, 2=top-right, 3=bottom-left, 4=bottom-right
    
    private var isSketchMode = false // Track if we're in sketch mode (no imported/captured image)

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

    var onCropApplied: ((Bitmap) -> Unit)? = null
    var onCropCanceled: (() -> Unit)? = null
    var onUndoAction: (() -> Unit)? = null // Simple callback for undo operations
    var onRedoAction: (() -> Unit)? = null // Simple callback for redo operations
    var onBitmapChanged: ((EditAction.BitmapChange) -> Unit)? = null // Keep for compatibility

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
        currentDrawingTool = when (drawingState.drawMode) {
            DrawMode.PEN -> PenTool()
            DrawMode.CIRCLE -> CircleTool()
            DrawMode.SQUARE -> SquareTool()
        }
    }

    fun setBitmap(bitmap: Bitmap?) {
        baseBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        // Reset history when new bitmap is loaded
        bitmapHistory.clear()
        currentHistoryIndex = -1
        if (baseBitmap != null) {
            saveCurrentState() // Save initial state
        }
        background = resources.getDrawable(R.drawable.outer_bounds, null)

        updateImageMatrix()
        invalidate()
        post {
            if (currentTool == ToolType.CROP && isCropModeActive) {
                setCropMode(currentCropMode)
            }
        }
    }

    fun updateBitmapWithHistory(bitmap: Bitmap?) {
        baseBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        saveCurrentState()
        updateImageMatrix()
        invalidate()
    }
    
    private fun saveCurrentState() {
        baseBitmap?.let { bitmap ->
            if (currentHistoryIndex < bitmapHistory.size - 1) {
                bitmapHistory.subList(currentHistoryIndex + 1, bitmapHistory.size).clear()
            }
            bitmapHistory.add(bitmap.copy(Bitmap.Config.ARGB_8888, true))
            currentHistoryIndex = bitmapHistory.size - 1
            
            if (bitmapHistory.size > 50) {
                bitmapHistory.removeAt(0)
                currentHistoryIndex = bitmapHistory.size - 1
            }
        }
    }
    
    fun undo(): Bitmap? {
        if (currentHistoryIndex > 0) {
            currentHistoryIndex--
            baseBitmap = bitmapHistory[currentHistoryIndex].copy(Bitmap.Config.ARGB_8888, true)
            updateImageMatrix()
            invalidate()
            return baseBitmap
        }
        return null
    }
    
    fun redo(): Bitmap? {
        if (currentHistoryIndex < bitmapHistory.size - 1) {
            currentHistoryIndex++
            baseBitmap = bitmapHistory[currentHistoryIndex].copy(Bitmap.Config.ARGB_8888, true)
            updateImageMatrix()
            invalidate()
            return baseBitmap
        }
        return null
    }
    
    fun canUndo(): Boolean = currentHistoryIndex > 0
    fun canRedo(): Boolean = currentHistoryIndex < bitmapHistory.size - 1

    fun setSketchMode(isSketch: Boolean) {
        this.isSketchMode = isSketch
        invalidate()
    }

    fun getBaseBitmap(): Bitmap? = baseBitmap

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

    fun applyCrop(): Bitmap? {
        if (baseBitmap == null || cropRect.isEmpty) return null

        val previousBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
        val bitmapWithDrawings = baseBitmap ?: return null

        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        val imageCropRect = RectF()
        inverseMatrix.mapRect(imageCropRect, cropRect)

        val left = imageCropRect.left.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val top = imageCropRect.top.coerceIn(0f, bitmapWithDrawings.height.toFloat())
        val right = imageCropRect.right.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val bottom = imageCropRect.bottom.coerceIn(0f, bitmapWithDrawings.height.toFloat())

        if (right <= left || bottom <= top) return null

        val croppedBitmap = Bitmap.createBitmap(
            bitmapWithDrawings,
            left.toInt(),
            top.toInt(),
            (right - left).toInt(),
            (bottom - top).toInt()
        )

        baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        saveCurrentState()
        
        val cropAction = CropAction(previousBitmap, RectF(cropRect), currentCropMode)
        baseBitmap?.let { newBitmap ->
            val editAction = EditAction.BitmapChange(previousBitmap, newBitmap.copy(Bitmap.Config.ARGB_8888, true), cropAction = cropAction)
            onBitmapChanged?.invoke(editAction)
        }
        
        cropRect.setEmpty()
        scaleFactor = 1.0f
        translationX = 0f
        translationY = 0f
        updateImageMatrix()
        invalidate()
        onCropApplied?.invoke(baseBitmap!!)

        return baseBitmap
    }

    fun getDrawing(): Bitmap? {
        return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun getDrawingOnTransparent(): Bitmap? {
        return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }
    
    fun getTransparentDrawing(): Bitmap? {
        return getDrawingOnTransparent()
    }
    
    fun getSketchDrawingOnWhite(): Bitmap? {
        // First, get the drawing with any adjustments.
        val drawingBitmap = getFinalBitmap() ?: return null

        // Create a new bitmap with a white background.
        val whiteBitmap = Bitmap.createBitmap(drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whiteBitmap)
        canvas.drawColor(Color.WHITE)

        // Draw the sketch on top of the white background.
        canvas.drawBitmap(drawingBitmap, 0f, 0f, null)
        
        return whiteBitmap
    }

    fun getFinalBitmap(): Bitmap? {
        if (baseBitmap == null) {
            return null
        }

        // Only apply adjustments if they're not default values
        val hasAdjustments = brightness != 0f || contrast != 1f || saturation != 1f
        if (!hasAdjustments) {
            return baseBitmap
        }

        // Create a new bitmap with the adjustments baked in
        val adjustedBitmap = Bitmap.createBitmap(baseBitmap!!.width, baseBitmap!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(adjustedBitmap)
        
        // Apply the same color filter used for display
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

    fun mergeDrawingStrokeIntoBitmap(action: DrawingAction) {
        if (baseBitmap == null) return
        
        val previousBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
        
        val canvas = Canvas(baseBitmap!!)
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        canvas.concat(inverseMatrix)
        canvas.drawPath(action.path, action.paint)
        
        saveCurrentState()
        
        baseBitmap?.let { newBitmap ->
            val editAction = EditAction.BitmapChange(previousBitmap, newBitmap.copy(Bitmap.Config.ARGB_8888, true), associatedStroke = action)
            onBitmapChanged?.invoke(editAction)
        }
        
        invalidate()
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

            if (isSketchMode) {
                canvas.drawColor(Color.WHITE)
            }

            if (it.hasAlpha() && !isSketchMode) {
                val checker = CheckerDrawable()
                imageBounds.roundOut(checker.bounds)
                checker.draw(canvas)
            }
            
            canvas.drawBitmap(it, imageMatrix, imagePaint)
            canvas.restore()
        }

        currentDrawingTool.onDraw(canvas, paint)

        if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
            drawCropOverlay(canvas)
        }
    }

    private fun drawCropOverlay(canvas: Canvas) {
        val overlayPaint = Paint().apply {
            color = Color.BLACK
            alpha = 128
        }
        val overlayPath = Path()
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
        }
    }

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
        if (!isZooming && event.pointerCount <= 1) {
            return false
        }

        if (isDrawing && currentTool == ToolType.DRAW) {
            currentDrawingTool.onTouchEvent(
                MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_CANCEL, event.x, event.y, 0),
                paint
            )
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
        when (event.action) {
            MotionEvent.ACTION_DOWN -> isDrawing = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDrawing = false
        }
        
        val screenSpaceAction = currentDrawingTool.onTouchEvent(event, paint)
        screenSpaceAction?.let { action ->
            mergeDrawingStrokeIntoBitmap(action)
        }
        invalidate()
        return true
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
        
        // Check for resize handle first
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

        // Check if touching inside crop rect
        if (cropRect.contains(x, y)) {
            validateAndCorrectCropRect()
            isMovingCropRect = true
            cropStartX = x
            cropStartY = y
            cropStartLeft = cropRect.left
            cropStartTop = cropRect.top
            cropStartRight = cropRect.right
            cropStartBottom = cropRect.bottom
            return true
        }

        // Start new crop if in crop mode
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

    private fun moveCropRect(x: Float, y: Float) {
        var dx = x - cropStartX
        var dy = y - cropStartY
        val visibleBounds = getVisibleImageBounds()
        
        // Constrain movement within visible bounds
        if (cropStartLeft + dx < visibleBounds.left) dx = visibleBounds.left - cropStartLeft
        if (cropStartTop + dy < visibleBounds.top) dy = visibleBounds.top - cropStartTop
        if (cropStartRight + dx > visibleBounds.right) dx = visibleBounds.right - cropStartRight
        if (cropStartBottom + dy > visibleBounds.bottom) dy = visibleBounds.bottom - cropStartBottom
        
        cropRect.set(cropStartLeft + dx, cropStartTop + dy, cropStartRight + dx, cropStartBottom + dy)
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
        if (cropRect.left + (height*targetAspectRatio) > visibleBounds.right) {
            // Recalculate based on width if constrained by right edge
        }
        
        cropRect.bottom = cropRect.top + height
        invalidate()
    }

    /**
     * Validates the crop rectangle on touch, ensuring it respects aspect ratio and bounds.
     * This prevents issues where zooming/panning can leave the marquee out of bounds.
     */
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

        // 1. Clamp the center of the crop rectangle to be within the visible bounds.
        val centerX = cropRect.centerX().coerceIn(visibleBounds.left, visibleBounds.right)
        val centerY = cropRect.centerY().coerceIn(visibleBounds.top, visibleBounds.bottom)

        var width = cropRect.width()
        var height = cropRect.height()

        // 2. If an aspect ratio is set, enforce it.
        targetAspectRatio?.let { ratio ->
            if (width / height > ratio) {
                width = height * ratio
            } else {
                height = width / ratio
            }
        }

        // 3. Scale down the size if it exceeds the maximum possible size from the clamped center.
        val maxAllowedWidth = 2 * kotlin.math.min(centerX - visibleBounds.left, visibleBounds.right - centerX)
        val maxAllowedHeight = 2 * kotlin.math.min(centerY - visibleBounds.top, visibleBounds.bottom - centerY)

        if (width > maxAllowedWidth || height > maxAllowedHeight) {
            val widthScale = maxAllowedWidth / width
            val heightScale = maxAllowedHeight / height
            val scale = kotlin.math.min(widthScale, heightScale)
            width *= scale
            height *= scale
        }

        // 4. Set the final, corrected rectangle and invalidate the view.
        cropRect.set(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
        invalidate()
    }

    private fun getResizeHandle(x: Float, y: Float): Int {
        val cornerSize = 60f
        if (RectF(cropRect.left - cornerSize, cropRect.top - cornerSize, cropRect.left + cornerSize, cropRect.top + cornerSize).contains(x, y)) return 1
        if (RectF(cropRect.right - cornerSize, cropRect.top - cornerSize, cropRect.right + cornerSize, cropRect.top + cornerSize).contains(x, y)) return 2
        if (RectF(cropRect.left - cornerSize, cropRect.bottom - cornerSize, cropRect.left + cornerSize, cropRect.bottom + cornerSize).contains(x, y)) return 3
        if (RectF(cropRect.right - cornerSize, cropRect.bottom - cornerSize, cropRect.right + cornerSize, cropRect.bottom + cornerSize).contains(x, y)) return 4
        return 0
    }

    private fun resizeCropRect(x: Float, y: Float) {
        val aspectRatio = getAspectRatio()
        
        if (aspectRatio != null) {
            resizeCropRectWithAspectRatio(x, y, aspectRatio)
        } else {
            resizeCropRectFreeform(x, y)
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
            1 -> Pair(cropRect.right, cropRect.bottom)
            2 -> Pair(cropRect.left, cropRect.bottom)
            3 -> Pair(cropRect.right, cropRect.top)
            4 -> Pair(cropRect.left, cropRect.top)
            else -> Pair(cropRect.left, cropRect.top) // Should not happen
        }
    }

    private fun calculateAspectRatioSize(x: Float, y: Float, fixedX: Float, fixedY: Float, aspectRatio: Float): Pair<Float, Float> {
        val signX = if (resizeHandle == 1 || resizeHandle == 3) -1 else 1
        val signY = if (resizeHandle == 1 || resizeHandle == 2) -1 else 1

        var newWidth = (x - fixedX) * signX
        var newHeight = (y - fixedY) * signY

        // Prevent negative sizes
        newWidth = newWidth.coerceAtLeast(0f)
        newHeight = newHeight.coerceAtLeast(0f)

        // Apply aspect ratio constraint
        if (newWidth / newHeight > aspectRatio) {
            newWidth = newHeight * aspectRatio
        } else {
            newHeight = newWidth / aspectRatio
        }

        return Pair(newWidth, newHeight)
    }

    private fun applySizeConstraints(newWidth: Float, newHeight: Float, fixedX: Float, fixedY: Float, aspectRatio: Float): Pair<Float, Float> {
        val minCropSize = 50f
        
        // Apply minimum size constraints
        val minHeight = if (aspectRatio > 1) minCropSize else minCropSize / aspectRatio
        val minWidth = if (aspectRatio < 1) minCropSize else minCropSize * aspectRatio
        
        var constrainedWidth = newWidth.coerceAtLeast(minWidth)
        var constrainedHeight = newHeight.coerceAtLeast(minHeight)

        // Apply boundary constraints
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
                cropRect.left = x.coerceAtMost(cropRect.right - minCropSize)
                cropRect.top = y.coerceAtMost(cropRect.bottom - minCropSize)
            }
            2 -> {
                cropRect.right = x.coerceAtLeast(cropRect.left + minCropSize)
                cropRect.top = y.coerceAtMost(cropRect.bottom - minCropSize)
            }
            3 -> {
                cropRect.left = x.coerceAtMost(cropRect.right - minCropSize)
                cropRect.bottom = y.coerceAtLeast(cropRect.top + minCropSize)
            }
            4 -> {
                cropRect.right = x.coerceAtLeast(cropRect.left + minCropSize)
                cropRect.bottom = y.coerceAtLeast(cropRect.top + minCropSize)
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
        } else { // Freeform
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
            0f, 0f, 0f, 1f, 0f  // Preserve alpha channel completely
        ))

        // Apply saturation while preserving alpha
        val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
        colorMatrix.postConcat(saturationMatrix)
        imagePaint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        imagePaint.xfermode = null
    }

    fun applyAdjustmentsToBitmap(): Bitmap? {
        if (baseBitmap == null) return null
        val adjustedBitmap = Bitmap.createBitmap(baseBitmap!!.width, baseBitmap!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(adjustedBitmap)
        val paint = Paint().apply { colorFilter = imagePaint.colorFilter }
        canvas.drawBitmap(baseBitmap!!, 0f, 0f, paint)
        return adjustedBitmap
    }

    fun resetAdjustments() {
        setAdjustments(0f, 1f, 1f)
    }

    fun getTransparentDrawingWithAdjustments(): Bitmap? {
        return getFinalBitmap()
    }
}