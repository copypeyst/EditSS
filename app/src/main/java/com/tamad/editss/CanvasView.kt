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
    private data class HistoryState(val bitmap: Bitmap)

    // Paints
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val currentPath = Path()
    private val cropPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f
    }
    private val cropCornerPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 192
    }
    private val overlayPaint = Paint().apply {
        color = Color.BLACK
        alpha = 128
    }
    private val imagePaint = Paint().apply {
        isFilterBitmap = true
        isDither = true
    }
    private val checkerDrawable = CheckerDrawable()
    private val overlayPath = Path()

    // Core Variables
    private var baseBitmap: Bitmap? = null
    private var drawingCanvas: Canvas? = null
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val imageBounds = RectF()
    private val viewBounds = RectF()
    private var density = 1f

    // History Stack
    private val undoStack = Stack<Bitmap>()
    private val redoStack = Stack<Bitmap>()

    // Tool State
    enum class ToolType { DRAW, CROP, ADJUST }
    private var currentTool: ToolType = ToolType.DRAW
    private var currentDrawingTool: DrawingTool = PenTool()
    
    // Transform
    private var scaleFactor = 1.0f
    private var translationX = 0f
    private var translationY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var isZooming = false
    private var isDrawing = false
    private var lastPointerCount = 0
    private var isSketchMode = false
    private var lastSavedHash = 0

    // Crop State
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

    // Setup Bitmap
    fun setBitmap(bitmap: Bitmap?) {
        undoStack.clear()
        redoStack.clear()
        
        baseBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        baseBitmap?.let { drawingCanvas = Canvas(it) }
        
        resetTransform()
        markAsSaved()
        invalidate()
    }

    fun getBaseBitmap(): Bitmap? = baseBitmap

    // History Logic
    private fun saveStateForUndo() {
        val bmp = baseBitmap ?: return
        // Save copy of current pixels
        undoStack.push(bmp.copy(Bitmap.Config.ARGB_8888, true))
        if (undoStack.size > 10) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun undo(): Bitmap? {
        if (undoStack.isNotEmpty()) {
            val current = baseBitmap
            if (current != null) redoStack.push(current)

            baseBitmap = undoStack.pop()
            baseBitmap?.let { drawingCanvas = Canvas(it) }
            
            updateImageMatrix()
            invalidate()
            onUndoAction?.invoke()
        }
        return baseBitmap
    }

    fun redo(): Bitmap? {
        if (redoStack.isNotEmpty()) {
            val current = baseBitmap
            if (current != null) undoStack.push(current)

            baseBitmap = redoStack.pop()
            baseBitmap?.let { drawingCanvas = Canvas(it) }
            
            updateImageMatrix()
            invalidate()
            onRedoAction?.invoke()
        }
        return baseBitmap
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun markAsSaved() {
        lastSavedHash = (undoStack.size + redoStack.size).hashCode()
    }
    
    fun hasUnsavedChanges(): Boolean {
        return (undoStack.size + redoStack.size).hashCode() != lastSavedHash
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
            canvas.clipRect(viewBounds) // Clip to image area

            if (isSketchMode) canvas.drawColor(Color.WHITE)
            if (bmp.hasAlpha() && !isSketchMode) checkerDrawable.draw(canvas)

            // Draw baked pixels
            canvas.drawBitmap(bmp, imageMatrix, imagePaint)

            // Draw current stroke (Finger down)
            if (isDrawing) {
                canvas.concat(imageMatrix)
                currentDrawingTool.onDraw(canvas, paint)
            }

            canvas.restore()
        }

        if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
            drawCropOverlay(canvas)
        }
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

    private fun handleDrawTouchEvent(event: MotionEvent): Boolean {
        if (baseBitmap == null) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                saveStateForUndo() // Save BEFORE drawing starts
                isDrawing = true
                currentDrawingTool.onTouchEvent(event, paint)
            }
            MotionEvent.ACTION_MOVE -> {
                currentDrawingTool.onTouchEvent(event, paint)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Bake stroke into bitmap
                val action = currentDrawingTool.onTouchEvent(event, paint)
                action?.let { bakeStroke(it) }
                isDrawing = false
            }
        }
        invalidate()
        return true
    }

    private fun bakeStroke(action: DrawingAction) {
        val bmp = baseBitmap ?: return
        val c = drawingCanvas ?: return
        
        // Draw directly to bitmap
        action.path.transform(inverseMatrix) // N/A here as we handle transform differently in tools usually, but standardizing
        // Standard tools return screen coordinates. We must map to Bitmap.
        
        // Re-apply matrix logic for baking
        // Tools usually track path in Screen Coords. We need Bitmap Coords.
        // Reset path transform done in onDraw
        val matrix = Matrix()
        imageMatrix.invert(matrix)
        action.path.transform(matrix)
        
        val drawPaint = Paint(paint)
        drawPaint.color = action.paint.color
        drawPaint.strokeWidth = action.paint.strokeWidth
        drawPaint.alpha = action.paint.alpha
        drawPaint.xfermode = action.paint.xfermode
        
        c.drawPath(action.path, drawPaint)
    }

    // Zoom Logic
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
        if (isDrawing) { isDrawing = false; invalidate() } // Cancel stroke
        
        if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount > 1) {
            val fx = (event.getX(0) + event.getX(1)) / 2
            val fy = (event.getY(0) + event.getY(1)) / 2
            if (lastFocusX != 0f) {
                translationX += fx - lastFocusX
                translationY += fy - lastFocusY
                updateImageMatrix()
                invalidate()
            }
            lastFocusX = fx; lastFocusY = fy
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) { lastFocusX = 0f; lastFocusY = 0f }
        return true
    }

    // Crop Logic
    fun setCropMode(cropMode: CropMode) {
        currentCropMode = cropMode
        isCropModeActive = true
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
        val bmp = baseBitmap ?: return null
        if (cropRect.isEmpty) return null

        saveStateForUndo()

        // Map Screen Rect to Bitmap Rect
        val matrix = Matrix()
        imageMatrix.invert(matrix)
        val bmpRect = RectF()
        matrix.mapRect(bmpRect, cropRect)

        // Clamp
        val l = bmpRect.left.coerceIn(0f, bmp.width.toFloat())
        val t = bmpRect.top.coerceIn(0f, bmp.height.toFloat())
        val r = bmpRect.right.coerceIn(0f, bmp.width.toFloat())
        val b = bmpRect.bottom.coerceIn(0f, bmp.height.toFloat())

        if (r <= l || b <= t) return null

        try {
            val cropped = Bitmap.createBitmap(bmp, l.toInt(), t.toInt(), (r - l).toInt(), (b - t).toInt())
            baseBitmap = cropped
            baseBitmap?.let { drawingCanvas = Canvas(it) }
            
            cropRect.setEmpty()
            resetTransform()
            invalidate()
            onCropApplied?.invoke(baseBitmap!!)
            return baseBitmap
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun handleCropTouchEvent(event: MotionEvent, x: Float, y: Float): Boolean {
        when (event.action) {
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
                    return true
                }
                if (cropRect.isEmpty && isCropModeActive) {
                    isCropping = true
                    cropRect.set(x, y, x, y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isResizingCropRect) resizeCropRect(x, y)
                else if (isMovingCropRect) moveCropRect(x, y)
                else if (isCropping) updateCropRect(x, y)
                
                clampCropRectToBounds()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (isCropping) enforceAspectRatio()
                isCropping = false; isMovingCropRect = false; isResizingCropRect = false; resizeHandle = 0
            }
        }
        return true
    }
    
    // Crop Math
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
        val ratio = getAspectRatio()
        
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
    
    private fun getAspectRatio(): Float? {
        return when(currentCropMode) {
            CropMode.SQUARE -> 1f
            CropMode.PORTRAIT -> 9f/16f
            CropMode.LANDSCAPE -> 16f/9f
            else -> null
        }
    }
    
    private fun enforceAspectRatio() {
        val ratio = getAspectRatio() ?: return
        val w = cropRect.width()
        cropRect.bottom = cropRect.top + w/ratio
        invalidate()
    }

    private fun initializeDefaultCropRect() {
        val b = getVisibleImageBounds()
        var w = b.width(); var h = b.height()
        val ratio = getAspectRatio()
        
        if (ratio != null) {
            if (w/h > ratio) w = h * ratio else h = w / ratio
        }
        
        val cx = b.centerX(); val cy = b.centerY()
        cropRect.set(cx - w/2, cy - h/2, cx + w/2, cy + h/2)
        clampCropRectToBounds()
    }
    
    private fun getVisibleImageBounds(): RectF {
        imageBounds.set(0f, 0f, baseBitmap?.width?.toFloat() ?: 0f, baseBitmap?.height?.toFloat() ?: 0f)
        imageMatrix.mapRect(imageBounds)
        return imageBounds
    }
    
    private fun clampCropRectToBounds() {
        val b = getVisibleImageBounds()
        cropRect.left = cropRect.left.coerceIn(b.left, b.right)
        cropRect.top = cropRect.top.coerceIn(b.top, b.bottom)
        cropRect.right = cropRect.right.coerceIn(b.left, b.right)
        cropRect.bottom = cropRect.bottom.coerceIn(b.top, b.bottom)
    }

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

    // Adjustments
    fun setAdjustments(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
        updateColorFilter()
        invalidate()
    }

    fun applyAdjustmentsToBitmap(): Bitmap? {
        val bmp = baseBitmap ?: return null
        saveStateForUndo()
        
        val result = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(result)
        val p = Paint().apply { colorFilter = imagePaint.colorFilter }
        c.drawBitmap(bmp, 0f, 0f, p)
        
        baseBitmap = result
        baseBitmap?.let { drawingCanvas = Canvas(it) }
        
        resetAdjustments()
        invalidate()
        return baseBitmap
    }

    fun clearAdjustments() { resetAdjustments(); invalidate() }
    fun resetAdjustments() {
        brightness = 0f; contrast = 1f; saturation = 1f
        imagePaint.colorFilter = null
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

    // Helpers
    fun getDrawing(): Bitmap? = baseBitmap
    fun getTransparentDrawingWithAdjustments(): Bitmap? = baseBitmap
    fun getSketchDrawingOnWhite(): Bitmap? = baseBitmap?.let { convertTransparentToWhite(it) }
    
    fun convertTransparentToWhite(bitmap: Bitmap): Bitmap {
        val white = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(white)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bitmap, 0f, 0f, null)
        return white
    }

    fun setToolType(toolType: ToolType) {
        this.currentTool = toolType
        if (toolType == ToolType.CROP && isCropModeActive) initializeDefaultCropRect()
        invalidate()
    }
    
    fun setCropModeInactive() { isCropModeActive = false; cropRect.setEmpty(); invalidate() }
    fun setSketchMode(isSketch: Boolean) { this.isSketchMode = isSketch; invalidate() }

    private fun updateImageMatrix() {
        val bmp = baseBitmap ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        if (bw == 0f || bh == 0f) return
        
        val s = Math.min(vw/bw, vh/bh)
        val dx = (vw - bw*s)/2f; val dy = (vh - bh*s)/2f
        
        imageMatrix.setScale(s*scaleFactor, s*scaleFactor)
        imageMatrix.postTranslate(dx+translationX, dy+translationY)
        
        // Update View Bounds
        viewBounds.set(0f, 0f, bw, bh)
        imageMatrix.mapRect(viewBounds)
        viewBounds.roundOut(checkerDrawable.bounds)
    }
    
    private fun resetTransform() {
        scaleFactor = 1.0f; translationX = 0f; translationY = 0f
        updateImageMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageMatrix()
    }
}