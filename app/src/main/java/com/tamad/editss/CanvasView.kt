package com.tamad.editss

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Stack
import kotlin.math.hypot

class CanvasView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    // 1. Data Models
    private data class StrokeData(
        val path: Path,
        val color: Int,
        val width: Float,
        val alpha: Int,
        val xfermode: Xfermode? = null
    )

    // 2. Paints
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val cropPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
    }
    private val cropCornerPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 192
    }
    private val overlayPaint = Paint().apply {
        color = Color.BLACK
        alpha = 128
    }
    private val imagePaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }

    // 3. Core Variables
    private val overlayPath = Path()
    private val checkerDrawable = CheckerDrawable()
    private var baseBitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val imageBounds = RectF()
    private var density = 1f

    // 4. History Stacks
    private val strokeStack = Stack<StrokeData>()
    private val redoStack = Stack<StrokeData>()
    private val bitmapCheckpointStack = Stack<Bitmap>()
    private var lastSavedStateHash = 0 

    // 5. Tool State
    private var currentDrawingTool: DrawingTool = PenTool()
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

    // 6. Touch Coordinates
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    private var cropStartX = 0f
    private var cropStartY = 0f
    private var cropStartLeft = 0f
    private var cropStartTop = 0f
    private var cropStartRight = 0f
    private var cropStartBottom = 0f

    // 7. Adjustment Variables
    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f

    // 8. Callbacks
    var onCropApplied: ((Bitmap) -> Unit)? = null
    var onCropCanceled: (() -> Unit)? = null
    var onUndoAction: (() -> Unit)? = null
    var onRedoAction: (() -> Unit)? = null
    var onBitmapChanged: ((EditAction.BitmapChange) -> Unit)? = null

    init {
        density = context.resources.displayMetrics.density
        background = ContextCompat.getDrawable(context, R.drawable.outer_bounds)
    }

    enum class ToolType { DRAW, CROP, ADJUST }

    // 9. Bitmap Management
    fun setBitmap(bitmap: Bitmap?) {
        strokeStack.clear()
        redoStack.clear()
        bitmapCheckpointStack.clear()
        
        baseBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        updateImageMatrix()
        invalidate()
        markAsSaved()
    }

    fun getBaseBitmap(): Bitmap? = baseBitmap

    // 10. Save State Tracking
    fun markAsSaved() {
        // Calculate a simple hash of the current state size
        lastSavedStateHash = getCurrentStateHash()
    }

    fun hasUnsavedChanges(): Boolean {
        return getCurrentStateHash() != lastSavedStateHash
    }

    private fun getCurrentStateHash(): Int {
        return strokeStack.size + (bitmapCheckpointStack.size * 1000)
    }

    // 11. Undo / Redo Engine
    fun undo(): Bitmap? {
        if (strokeStack.isNotEmpty()) {
            // Undo Stroke
            redoStack.push(strokeStack.pop())
            invalidate()
            onUndoAction?.invoke()
        } else if (bitmapCheckpointStack.isNotEmpty()) {
            // Undo Heavy Action (Crop/Adjust)
            baseBitmap = bitmapCheckpointStack.pop()
            // When we undo a checkpoint, we clear redo because the branch changed
            redoStack.clear() 
            updateImageMatrix()
            invalidate()
            onUndoAction?.invoke()
        }
        return baseBitmap
    }

    fun redo(): Bitmap? {
        if (redoStack.isNotEmpty()) {
            strokeStack.push(redoStack.pop())
            invalidate()
            onRedoAction?.invoke()
        }
        // Note: Redoing heavy checkpoints is complex, usually standard apps only redo strokes
        // unless we implement a separate Action Command stack. 
        // For now, this matches standard lightweight behavior.
        return baseBitmap
    }

    fun canUndo(): Boolean = strokeStack.isNotEmpty() || bitmapCheckpointStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    // 12. Drawing Implementation
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
            canvas.save()
            canvas.clipRect(imageBounds)

            if (isSketchMode) canvas.drawColor(Color.WHITE)
            if (it.hasAlpha() && !isSketchMode) checkerDrawable.draw(canvas)

            canvas.drawBitmap(it, imageMatrix, imagePaint)
            
            canvas.concat(imageMatrix)
            
            // Draw History
            for (stroke in strokeStack) {
                paint.color = stroke.color
                paint.strokeWidth = stroke.width
                paint.alpha = stroke.alpha
                paint.xfermode = stroke.xfermode
                canvas.drawPath(stroke.path, paint)
            }
            
            paint.xfermode = null
            
            // Draw Active Stroke
            if (isDrawing) {
                currentDrawingTool.onDraw(canvas, paint)
            }
            
            canvas.restore()
        }

        if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
            drawCropOverlay(canvas)
        }
    }

    private fun mergeDrawingStrokeIntoBitmap(action: DrawingAction) {
        strokeStack.push(StrokeData(
            Path(action.path),
            action.paint.color,
            action.paint.strokeWidth,
            action.paint.alpha,
            action.paint.xfermode
        ))
        redoStack.clear()
    }

    // 13. Touch Handling
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

        if (handleMultiTouchGesture(event)) return true

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
            isDrawing = false
            invalidate()
        }

        if (scaleFactor > 1.0f) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) handlePanning(event)
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
        screenSpaceAction?.let { action ->
             val inverseMatrix = Matrix()
             imageMatrix.invert(inverseMatrix)
             action.path.transform(inverseMatrix)
             mergeDrawingStrokeIntoBitmap(action)
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> isDrawing = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDrawing = false
        }
        invalidate()
        return true
    }

    // 14. Crop Logic & Tool Mixing
    fun setCropMode(cropMode: CropMode) {
        this.currentCropMode = cropMode
        this.isCropModeActive = true
        cropRect.setEmpty()
        if (currentTool == ToolType.CROP) initializeDefaultCropRect()
        invalidate()
    }

    fun cancelCrop() {
        cropRect.setEmpty()
        invalidate()
        onCropCanceled?.invoke()
    }

    fun applyCrop(): Bitmap? {
        if (baseBitmap == null || cropRect.isEmpty) return null

        // CHECKPOINT 1: Bake Strokes
        // If we have pending strokes, we must bake them into the bitmap 
        // BEFORE we crop, otherwise the paths will misalign.
        val flattenedBitmap = getFinalBitmap() ?: return null
        
        // CHECKPOINT 2: Save to History
        // Save the FULL current state (Base + Strokes) before modification
        baseBitmap?.let { 
            // We reconstruct the pre-crop state by getting FinalBitmap but using original dimensions
            // Actually, the simplest safe checkpoint is the current visual state:
             bitmapCheckpointStack.push(flattenedBitmap.copy(Bitmap.Config.ARGB_8888, true))
             if (bitmapCheckpointStack.size > 5) bitmapCheckpointStack.removeAt(0)
        }

        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        val imageCropRect = RectF()
        inverseMatrix.mapRect(imageCropRect, cropRect)

        val left = imageCropRect.left.coerceIn(0f, flattenedBitmap.width.toFloat())
        val top = imageCropRect.top.coerceIn(0f, flattenedBitmap.height.toFloat())
        val right = imageCropRect.right.coerceIn(0f, flattenedBitmap.width.toFloat())
        val bottom = imageCropRect.bottom.coerceIn(0f, flattenedBitmap.height.toFloat())

        if (right <= left || bottom <= top) return null

        try {
            val croppedBitmap = Bitmap.createBitmap(
                flattenedBitmap,
                left.toInt(),
                top.toInt(),
                (right - left).toInt(),
                (bottom - top).toInt()
            )

            baseBitmap = croppedBitmap
            // RESET STACKS: Since we baked strokes into the bitmap, we clear the vector stack
            strokeStack.clear()
            redoStack.clear()
            
            cropRect.setEmpty()
            scaleFactor = 1.0f
            translationX = 0f
            translationY = 0f
            
            updateImageMatrix()
            invalidate()
            onCropApplied?.invoke(baseBitmap!!)

            return baseBitmap
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            return null
        }
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
                calculateTouchOffsets(x, y)
                return true
            }
        }

        if (cropRect.contains(x, y)) {
            validateAndCorrectCropRect()
            isMovingCropRect = true
            calculateTouchOffsets(x, y)
            cropStartX = x
            cropStartY = y
            return true
        }

        if (cropRect.isEmpty && isCropModeActive) {
            isCropping = true
            cropRect.set(x, y, x, y)
            return true
        }
        return true
    }
    
    private fun calculateTouchOffsets(x: Float, y: Float) {
        touchOffsetX = if (resizeHandle > 0) {
             when (resizeHandle) {
                 1, 3 -> cropRect.left - x
                 else -> cropRect.right - x
             }
        } else {
             cropRect.left - x
        }
        
        touchOffsetY = if (resizeHandle > 0) {
             when (resizeHandle) {
                 1, 2 -> cropRect.top - y
                 else -> cropRect.bottom - y
             }
        } else {
             cropRect.top - y
        }
        
        cropStartLeft = cropRect.left
        cropStartTop = cropRect.top
        cropStartRight = cropRect.right
        cropStartBottom = cropRect.bottom
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
            var width = visibleBounds.width()
            var height = visibleBounds.height()
            
            when (currentCropMode) {
                CropMode.SQUARE -> {
                    val size = Math.min(width, height)
                    width = size; height = size
                }
                CropMode.PORTRAIT -> width = height * 9 / 16f
                CropMode.LANDSCAPE -> height = width * 9 / 16f
                else -> {}
            }
            
            val cx = visibleBounds.centerX()
            val cy = visibleBounds.centerY()
            cropRect.set(cx - width/2, cy - height/2, cx + width/2, cy + height/2)
            clampCropRectToBounds()
        }
    }

    private fun getVisibleImageBounds(): RectF {
        return RectF(
            Math.max(imageBounds.left, 0f),
            Math.max(imageBounds.top, 0f),
            Math.min(imageBounds.right, width.toFloat()),
            Math.min(imageBounds.bottom, height.toFloat())
        )
    }

    private fun clampCropRectToBounds() {
        if (imageBounds.width() <= 0) return
        val bounds = getVisibleImageBounds()
        cropRect.left = cropRect.left.coerceIn(bounds.left, bounds.right)
        cropRect.top = cropRect.top.coerceIn(bounds.top, bounds.bottom)
        cropRect.right = cropRect.right.coerceIn(bounds.left, bounds.right)
        cropRect.bottom = cropRect.bottom.coerceIn(bounds.top, bounds.bottom)
    }

    private fun moveCropRect(x: Float, y: Float) {
        val width = cropRect.width()
        val height = cropRect.height()
        var left = x + touchOffsetX
        var top = y + touchOffsetY
        
        val bounds = getVisibleImageBounds()
        left = left.coerceIn(bounds.left, bounds.right - width)
        top = top.coerceIn(bounds.top, bounds.bottom - height)
        
        cropRect.set(left, top, left + width, top + height)
        invalidate()
    }
    
    private fun enforceAspectRatio() {
        if (currentCropMode == CropMode.FREEFORM) return
        val ratio = getAspectRatio() ?: return
        val w = cropRect.width()
        cropRect.bottom = cropRect.top + (w / ratio)
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
        val targetX = x + touchOffsetX
        val targetY = y + touchOffsetY
        val ratio = getAspectRatio()
        if (ratio != null) resizeCropRectWithAspectRatio(targetX, targetY, ratio)
        else resizeCropRectFreeform(targetX, targetY)
    }
    
    private fun resizeCropRectFreeform(x: Float, y: Float) {
        val min = 50f
        when (resizeHandle) {
            1 -> { cropRect.left = x.coerceAtMost(cropRect.right - min); cropRect.top = y.coerceAtMost(cropRect.bottom - min) }
            2 -> { cropRect.right = x.coerceAtLeast(cropRect.left + min); cropRect.top = y.coerceAtMost(cropRect.bottom - min) }
            3 -> { cropRect.left = x.coerceAtMost(cropRect.right - min); cropRect.bottom = y.coerceAtLeast(cropRect.top + min) }
            4 -> { cropRect.right = x.coerceAtLeast(cropRect.left + min); cropRect.bottom = y.coerceAtLeast(cropRect.top + min) }
        }
    }

    private fun resizeCropRectWithAspectRatio(x: Float, y: Float, ratio: Float) {
        val newW = (x - cropRect.left).coerceAtLeast(50f)
        cropRect.right = cropRect.left + newW
        cropRect.bottom = cropRect.top + (newW / ratio)
    }

    private fun getAspectRatio(): Float? {
        return when (currentCropMode) {
            CropMode.SQUARE -> 1f
            CropMode.PORTRAIT -> 9f / 16f
            CropMode.LANDSCAPE -> 16f / 9f
            else -> null
        }
    }

    private fun updateCropRect(x: Float, y: Float) {
        cropRect.right = x.coerceAtLeast(cropRect.left + 50f)
        cropRect.bottom = y.coerceAtLeast(cropRect.top + 50f)
    }
    
    private fun validateAndCorrectCropRect() { }

    // 15. Adjustment Logic
    fun setAdjustments(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        updateColorFilter()
        invalidate()
    }

    fun clearAdjustments() {
        resetAdjustments()
    }

    fun resetAdjustments() {
        setAdjustments(0f, 1f, 1f)
    }
    
    fun applyAdjustmentsToBitmap(): Bitmap? {
        if (baseBitmap == null) return null

        // 1. Save Checkpoint (Current visual state)
        val currentVisualState = getFinalBitmap() ?: return null
        bitmapCheckpointStack.push(currentVisualState.copy(Bitmap.Config.ARGB_8888, true))

        // 2. Commit Adjustment
        baseBitmap = currentVisualState
        
        // 3. Reset sliders
        resetAdjustments()
        
        // 4. Clear Stroke History (Bake)
        strokeStack.clear()
        redoStack.clear()
        
        invalidate()
        return baseBitmap
    }

    private fun updateColorFilter() {
        val cm = ColorMatrix()
        val trans = brightness + (1f - contrast) * 128f
        cm.set(floatArrayOf(
            contrast, 0f, 0f, 0f, trans,
            0f, contrast, 0f, 0f, trans,
            0f, 0f, contrast, 0f, trans,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(ColorMatrix().apply { setSaturation(saturation) })
        imagePaint.colorFilter = ColorMatrixColorFilter(cm)
    }

    // 16. Export & Helpers
    fun getDrawing(): Bitmap? = getFinalBitmap()

    fun getTransparentDrawingWithAdjustments(): Bitmap? = getFinalBitmap()

    fun getSketchDrawingOnWhite(): Bitmap? {
        val transparent = getFinalBitmap() ?: return null
        return convertTransparentToWhite(transparent)
    }

    fun convertTransparentToWhite(bitmap: Bitmap): Bitmap {
        val whiteBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whiteBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return whiteBitmap
    }

    fun getFinalBitmap(): Bitmap? {
        val source = baseBitmap ?: return null
        
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        val savePaint = Paint().apply {
            colorFilter = imagePaint.colorFilter
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(source, 0f, 0f, savePaint)
        
        for (stroke in strokeStack) {
            paint.color = stroke.color
            paint.strokeWidth = stroke.width
            paint.alpha = stroke.alpha
            paint.xfermode = stroke.xfermode
            canvas.drawPath(stroke.path, paint)
        }
        
        paint.xfermode = null
        return result
    }

    fun setToolType(toolType: ToolType) {
        this.currentTool = toolType
        if (toolType == ToolType.CROP && isCropModeActive) initializeDefaultCropRect()
        invalidate()
    }
    
    fun setCropModeInactive() {
        isCropModeActive = false
        cropRect.setEmpty()
        invalidate()
    }
    
    fun setSketchMode(isSketch: Boolean) {
        this.isSketchMode = isSketch
        invalidate()
    }

    private fun drawCropOverlay(canvas: Canvas) {
        overlayPath.reset()
        overlayPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        overlayPath.addRect(cropRect, Path.Direction.CCW)
        canvas.drawPath(overlayPath, overlayPaint)
        canvas.drawRect(cropRect, cropPaint)
        
        val s = 30f
        canvas.drawRect(cropRect.left, cropRect.top, cropRect.left + s, cropRect.top + s, cropCornerPaint)
        canvas.drawRect(cropRect.right - s, cropRect.top, cropRect.right, cropRect.top + s, cropCornerPaint)
        canvas.drawRect(cropRect.left, cropRect.bottom - s, cropRect.left + s, cropRect.bottom, cropCornerPaint)
        canvas.drawRect(cropRect.right - s, cropRect.bottom - s, cropRect.right, cropRect.bottom, cropCornerPaint)
    }

    private fun updateImageMatrix() {
        baseBitmap?.let {
            val viewW = width.toFloat()
            val viewH = height.toFloat()
            val bmpW = it.width.toFloat()
            val bmpH = it.height.toFloat()
            
            if (bmpW == 0f || bmpH == 0f) return

            val scale = Math.min(viewW / bmpW, viewH / bmpH)
            val dx = (viewW - bmpW * scale) / 2f
            val dy = (viewH - bmpH * scale) / 2f
            
            imageMatrix.setScale(scale * scaleFactor, scale * scaleFactor)
            imageMatrix.postTranslate(dx + translationX, dy + translationY)
            
            imageBounds.set(0f, 0f, bmpW, bmpH)
            imageMatrix.mapRect(imageBounds)
            imageBounds.roundOut(checkerDrawable.bounds)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageMatrix()
    }
}