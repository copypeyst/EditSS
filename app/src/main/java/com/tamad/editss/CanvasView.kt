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

    // Data Models
    private data class StrokeData(
        val path: Path,
        val color: Int,
        val width: Float,
        val alpha: Int,
        val xfermode: Xfermode?
    )

    // Paints
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
    private val checkerDrawable = CheckerDrawable()

    // Variables
    private val overlayPath = Path() // Fixed: Added missing variable
    private var baseBitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val imageBounds = RectF()
    private var density = 1f

    // History Stacks
    private val strokeStack = Stack<StrokeData>()
    private val redoStrokeStack = Stack<StrokeData>()
    private val bitmapUndoStack = Stack<Bitmap>()
    private val bitmapRedoStack = Stack<Bitmap>()

    // Tool State
    private var currentDrawingTool: DrawingTool = PenTool()
    private var currentTool: ToolType = ToolType.DRAW
    private var scaleFactor = 1.0f
    private var translationX = 0f
    private var translationY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var isZooming = false
    private var isDrawing = false
    private var lastPointerCount = 0
    private var isSketchMode = false
    private var lastSavedStackSize = 0

    // Crop State
    enum class ToolType { DRAW, CROP, ADJUST }
    private var currentCropMode: CropMode = CropMode.FREEFORM
    private var isCropModeActive = false
    private var isCropping = false
    private var cropRect = RectF()
    private var isMovingCropRect = false
    private var isResizingCropRect = false
    private var resizeHandle: Int = 0
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

    // Adjustments
    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f

    // Callbacks
    var onCropApplied: ((Bitmap) -> Unit)? = null
    var onCropCanceled: (() -> Unit)? = null
    var onUndoAction: (() -> Unit)? = null
    var onRedoAction: (() -> Unit)? = null
    var onBitmapChanged: ((EditAction.BitmapChange) -> Unit)? = null

    init {
        density = context.resources.displayMetrics.density
        background = ContextCompat.getDrawable(context, R.drawable.outer_bounds)
    }

    // Bitmap Logic
    fun setBitmap(bitmap: Bitmap?) {
        strokeStack.clear()
        redoStrokeStack.clear()
        bitmapUndoStack.clear()
        bitmapRedoStack.clear()
        
        baseBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        updateImageMatrix()
        invalidate()
        markAsSaved()
    }

    fun getBaseBitmap(): Bitmap? = baseBitmap

    // History Logic
    private fun pushBitmapCheckpoint() {
        val currentVisualState = getFinalBitmap() ?: return
        bitmapUndoStack.push(currentVisualState)
        if (bitmapUndoStack.size > 5) bitmapUndoStack.removeAt(0)
        bitmapRedoStack.clear()
    }

    fun undo(): Bitmap? {
        if (strokeStack.isNotEmpty()) {
            redoStrokeStack.push(strokeStack.pop())
            invalidate()
            onUndoAction?.invoke()
            return baseBitmap
        }
        
        if (bitmapUndoStack.isNotEmpty()) {
            val currentState = baseBitmap
            if (currentState != null) {
                bitmapRedoStack.push(currentState)
            }

            baseBitmap = bitmapUndoStack.pop()
            strokeStack.clear()
            
            updateImageMatrix()
            invalidate()
            onUndoAction?.invoke()
        }
        return baseBitmap
    }

    fun redo(): Bitmap? {
        if (redoStrokeStack.isNotEmpty()) {
            strokeStack.push(redoStrokeStack.pop())
            invalidate()
            onRedoAction?.invoke()
            return baseBitmap
        }
        
        if (bitmapRedoStack.isNotEmpty()) {
            pushBitmapCheckpoint()
            baseBitmap = bitmapRedoStack.pop()
            strokeStack.clear()
            
            updateImageMatrix()
            invalidate()
            onRedoAction?.invoke()
        }
        return baseBitmap
    }

    fun canUndo(): Boolean = strokeStack.isNotEmpty() || bitmapUndoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStrokeStack.isNotEmpty() || bitmapRedoStack.isNotEmpty()

    fun markAsSaved() {
        lastSavedStackSize = strokeStack.size + bitmapUndoStack.size
    }
    
    fun hasUnsavedChanges(): Boolean {
        return (strokeStack.size + bitmapUndoStack.size) != lastSavedStackSize
    }

    // Drawing Logic
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

        baseBitmap?.let { bmp ->
            canvas.save()
            canvas.clipRect(imageBounds)

            if (isSketchMode) canvas.drawColor(Color.WHITE)
            if (bmp.hasAlpha() && !isSketchMode) checkerDrawable.draw(canvas)

            canvas.drawBitmap(bmp, imageMatrix, imagePaint)

            canvas.concat(imageMatrix)
            
            for (stroke in strokeStack) {
                paint.color = stroke.color
                paint.strokeWidth = stroke.width
                paint.alpha = stroke.alpha
                paint.xfermode = stroke.xfermode
                canvas.drawPath(stroke.path, paint)
            }
            
            paint.xfermode = null
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
        redoStrokeStack.clear()
    }

    // Touch Logic
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

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (this@CanvasView.lastPointerCount < 2) return false
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f)
            
            val inv = Matrix()
            imageMatrix.invert(inv)
            val focus = floatArrayOf(detector.focusX, detector.focusY)
            val imgPt = floatArrayOf(0f, 0f)
            inv.mapPoints(imgPt, focus)
            
            updateImageMatrix()
            
            val screenPt = floatArrayOf(0f, 0f)
            imageMatrix.mapPoints(screenPt, imgPt)
            
            translationX += detector.focusX - screenPt[0]
            translationY += detector.focusY - screenPt[1]
            
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
            if (scaleFactor <= 1.0f) resetTransform()
        }
    })

    private fun handleMultiTouchGesture(event: MotionEvent): Boolean {
        if (!isZooming && event.pointerCount <= 1) return false
        if (isDrawing && currentTool == ToolType.DRAW) {
            isDrawing = false; invalidate()
        }
        if (scaleFactor > 1.0f) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) {
                        val fx = (event.getX(0) + event.getX(1)) / 2
                        val fy = (event.getY(0) + event.getY(1)) / 2
                        if (lastFocusX != 0f) {
                            translationX += fx - lastFocusX
                            translationY += fy - lastFocusY
                        }
                        lastFocusX = fx; lastFocusY = fy
                        updateImageMatrix()
                        invalidate()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> { lastFocusX = 0f; lastFocusY = 0f }
            }
        }
        return true
    }

    private fun handleDrawTouchEvent(event: MotionEvent): Boolean {
        val action = currentDrawingTool.onTouchEvent(event, paint)
        action?.let {
            val inv = Matrix()
            imageMatrix.invert(inv)
            it.path.transform(inv)
            mergeDrawingStrokeIntoBitmap(it)
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> isDrawing = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDrawing = false
        }
        invalidate()
        return true
    }

    // Crop Logic
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

        pushBitmapCheckpoint()

        val flattened = getFinalBitmap() ?: return null

        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        val imageCropRect = RectF()
        inverseMatrix.mapRect(imageCropRect, cropRect)

        val left = imageCropRect.left.coerceIn(0f, flattened.width.toFloat())
        val top = imageCropRect.top.coerceIn(0f, flattened.height.toFloat())
        val right = imageCropRect.right.coerceIn(0f, flattened.width.toFloat())
        val bottom = imageCropRect.bottom.coerceIn(0f, flattened.height.toFloat())

        if (right <= left || bottom <= top) return null

        try {
            val croppedBitmap = Bitmap.createBitmap(
                flattened,
                left.toInt(),
                top.toInt(),
                (right - left).toInt(),
                (bottom - top).toInt()
            )

            baseBitmap = croppedBitmap
            strokeStack.clear()
            redoStrokeStack.clear()
            
            cropRect.setEmpty()
            resetTransform()
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
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x; lastTouchY = y
                if (!cropRect.isEmpty) {
                    resizeHandle = getResizeHandle(x, y)
                    if (resizeHandle > 0) {
                        isResizingCropRect = true
                        setCropOffsets(x, y)
                        return true
                    }
                }
                if (cropRect.contains(x, y)) {
                    isMovingCropRect = true
                    setCropOffsets(x, y)
                    cropStartX = x; cropStartY = y
                    return true
                }
                if (cropRect.isEmpty && isCropModeActive) {
                    isCropping = true
                    cropRect.set(x, y, x, y)
                    return true
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isResizingCropRect) { resizeCropRect(x, y); clampCropRectToBounds(); invalidate() }
                else if (isMovingCropRect) moveCropRect(x, y)
                else if (isCropping) { updateCropRect(x, y); clampCropRectToBounds(); invalidate() }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (isCropping) { enforceAspectRatio(); updateCropRect(x, y) }
                isCropping = false; isMovingCropRect = false; isResizingCropRect = false; resizeHandle = 0
                true
            }
            else -> false
        }
    }
    
    private fun setCropOffsets(x: Float, y: Float) {
        touchOffsetX = if (resizeHandle > 0) {
             when(resizeHandle) { 1, 3 -> cropRect.left - x; else -> cropRect.right - x }
        } else cropRect.left - x
        touchOffsetY = if (resizeHandle > 0) {
             when(resizeHandle) { 1, 2 -> cropRect.top - y; else -> cropRect.bottom - y }
        } else cropRect.top - y
    }
    
    private fun moveCropRect(x: Float, y: Float) {
        val w = cropRect.width(); val h = cropRect.height()
        val bounds = getVisibleImageBounds()
        val l = (x + touchOffsetX).coerceIn(bounds.left, bounds.right - w)
        val t = (y + touchOffsetY).coerceIn(bounds.top, bounds.bottom - h)
        cropRect.set(l, t, l + w, t + h)
        invalidate()
    }

    private fun initializeDefaultCropRect() {
        val b = getVisibleImageBounds()
        if (b.width() <= 0) return
        var w = b.width(); var h = b.height()
        if (currentCropMode == CropMode.SQUARE) { val s = Math.min(w, h); w = s; h = s }
        else if (currentCropMode == CropMode.PORTRAIT) w = h * 9/16f
        else if (currentCropMode == CropMode.LANDSCAPE) h = w * 9/16f
        val cx = b.centerX(); val cy = b.centerY()
        cropRect.set(cx - w/2, cy - h/2, cx + w/2, cy + h/2)
        clampCropRectToBounds()
    }
    
    private fun getVisibleImageBounds(): RectF = RectF(
        Math.max(imageBounds.left, 0f), Math.max(imageBounds.top, 0f),
        Math.min(imageBounds.right, width.toFloat()), Math.min(imageBounds.bottom, height.toFloat())
    )
    
    private fun clampCropRectToBounds() {
        val b = getVisibleImageBounds()
        cropRect.left = cropRect.left.coerceIn(b.left, b.right)
        cropRect.top = cropRect.top.coerceIn(b.top, b.bottom)
        cropRect.right = cropRect.right.coerceIn(b.left, b.right)
        cropRect.bottom = cropRect.bottom.coerceIn(b.top, b.bottom)
    }
    
    private fun getResizeHandle(x: Float, y: Float): Int {
        val r = 48f * density
        if (hypot(x - cropRect.left, y - cropRect.top) <= r) return 1
        if (hypot(x - cropRect.right, y - cropRect.top) <= r) return 2
        if (hypot(x - cropRect.left, y - cropRect.bottom) <= r) return 3
        if (hypot(x - cropRect.right, y - cropRect.bottom) <= r) return 4
        return 0
    }
    
    private fun resizeCropRect(x: Float, y: Float) {
        val tx = x + touchOffsetX; val ty = y + touchOffsetY
        val ratio = when(currentCropMode) { CropMode.SQUARE->1f; CropMode.PORTRAIT->9/16f; CropMode.LANDSCAPE->16/9f; else->null }
        if (ratio != null) {
            val nw = (tx - cropRect.left).coerceAtLeast(50f)
            cropRect.right = cropRect.left + nw
            cropRect.bottom = cropRect.top + nw/ratio
        } else {
            val min = 50f
            when (resizeHandle) {
                1 -> { cropRect.left = tx.coerceAtMost(cropRect.right-min); cropRect.top = ty.coerceAtMost(cropRect.bottom-min) }
                2 -> { cropRect.right = tx.coerceAtLeast(cropRect.left+min); cropRect.top = ty.coerceAtMost(cropRect.bottom-min) }
                3 -> { cropRect.left = tx.coerceAtMost(cropRect.right-min); cropRect.bottom = ty.coerceAtLeast(cropRect.top+min) }
                4 -> { cropRect.right = tx.coerceAtLeast(cropRect.left+min); cropRect.bottom = ty.coerceAtLeast(cropRect.top+min) }
            }
        }
    }
    
    private fun updateCropRect(x: Float, y: Float) {
        cropRect.right = x.coerceAtLeast(cropRect.left + 50f)
        cropRect.bottom = y.coerceAtLeast(cropRect.top + 50f)
    }
    
    private fun enforceAspectRatio() {
        val ratio = when(currentCropMode) { CropMode.SQUARE->1f; CropMode.PORTRAIT->9/16f; CropMode.LANDSCAPE->16/9f; else->null }
        if (ratio != null) {
            val w = cropRect.width()
            cropRect.bottom = cropRect.top + w/ratio
            invalidate()
        }
    }

    // Adjustment Logic
    fun setAdjustments(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        updateColorFilter()
        invalidate()
    }

    fun clearAdjustments() {
        resetAdjustments()
        invalidate()
    }

    fun resetAdjustments() {
        this.brightness = 0f
        this.contrast = 1f
        this.saturation = 1f
        imagePaint.colorFilter = null
    }

    fun applyAdjustmentsToBitmap(): Bitmap? {
        if (baseBitmap == null) return null

        pushBitmapCheckpoint()

        val result = getFinalBitmap()
        baseBitmap = result
        
        resetAdjustments()
        strokeStack.clear()
        redoStrokeStack.clear()
        
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

    // Helper Methods
    fun getDrawing(): Bitmap? = getFinalBitmap()

    fun getTransparentDrawingWithAdjustments(): Bitmap? = getFinalBitmap()
    
    fun getSketchDrawingOnWhite(): Bitmap? {
        val b = getFinalBitmap() ?: return null
        return convertTransparentToWhite(b)
    }
    
    fun convertTransparentToWhite(bitmap: Bitmap): Bitmap {
        val white = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(white)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bitmap, 0f, 0f, null)
        return white
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
        
        return result
    }

    fun setToolType(toolType: ToolType) {
        this.currentTool = toolType
        if (toolType == ToolType.CROP && isCropModeActive) initializeDefaultCropRect()
        invalidate()
    }
    
    fun setCropModeInactive() { isCropModeActive = false; cropRect.setEmpty(); invalidate() }
    fun setSketchMode(isSketch: Boolean) { this.isSketchMode = isSketch; invalidate() }

    private fun drawCropOverlay(canvas: Canvas) {
        overlayPath.reset()
        overlayPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        overlayPath.addRect(cropRect, Path.Direction.CCW)
        canvas.drawPath(overlayPath, overlayPaint)
        canvas.drawRect(cropRect, cropPaint)
        val s = 30f
        canvas.drawRect(cropRect.left, cropRect.top, cropRect.left+s, cropRect.top+s, cropCornerPaint)
        canvas.drawRect(cropRect.right-s, cropRect.top, cropRect.right, cropRect.top+s, cropCornerPaint)
        canvas.drawRect(cropRect.left, cropRect.bottom-s, cropRect.left+s, cropRect.bottom, cropCornerPaint)
        canvas.drawRect(cropRect.right-s, cropRect.bottom-s, cropRect.right, cropRect.bottom, cropCornerPaint)
    }

    private fun updateImageMatrix() {
        baseBitmap?.let {
            val vw = width.toFloat(); val vh = height.toFloat()
            val bw = it.width.toFloat(); val bh = it.height.toFloat()
            if (bw == 0f || bh == 0f) return
            val s = Math.min(vw/bw, vh/bh)
            val dx = (vw - bw*s)/2f; val dy = (vh - bh*s)/2f
            imageMatrix.setScale(s*scaleFactor, s*scaleFactor)
            imageMatrix.postTranslate(dx+translationX, dy+translationY)
            imageBounds.set(0f, 0f, bw, bh)
            imageMatrix.mapRect(imageBounds)
            imageBounds.roundOut(checkerDrawable.bounds)
        }
    }

    private fun resetTransform() {
        scaleFactor = 1.0f
        translationX = 0f
        translationY = 0f
        updateImageMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageMatrix()
    }
}