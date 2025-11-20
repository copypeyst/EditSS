package com.tamad.editss

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.LinkedList
import kotlin.math.hypot

class CanvasView(context: Context, attrs: AttributeSet) : View(context, attrs) {

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

    // Bitmaps
    private var baseBitmap: Bitmap? = null // This is the "Proxy" (Low Res) for UI
    private var originalHighResBitmap: Bitmap? = null // The original full quality image
    
    private val imageMatrix = android.graphics.Matrix()
    private val imageBounds = RectF()

    // Undo/Redo System (RAM Only)
    private data class HistoryItem(
        val bitmap: Bitmap, // The proxy state
        val action: RestoreAction // Data needed to replay on high-res
    )

    private sealed class RestoreAction {
        object None : RestoreAction()
        data class Draw(val path: Path, val paint: Paint) : RestoreAction()
        data class Crop(val cropRect: RectF, val canvasWidth: Int, val canvasHeight: Int) : RestoreAction()
        data class Adjust(val brightness: Float, val contrast: Float, val saturation: Float) : RestoreAction()
    }

    private val historyStack = LinkedList<HistoryItem>()
    private var currentHistoryIndex = -1
    private val MAX_RAM_USAGE = 500 * 1024 * 1024L // 500MB Limit

    // Canvas State
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
    var onUndoAction: (() -> Unit)? = null
    var onRedoAction: (() -> Unit)? = null
    var onBitmapChanged: ((EditAction.BitmapChange) -> Unit)? = null

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

    // RAM-Based History Management

    private fun saveCurrentState(action: RestoreAction) {
        val bitmapToSave = baseBitmap?.copy(Bitmap.Config.ARGB_8888, true) ?: return

        // If we are in the middle of the stack and draw, remove forward history (Redo paths)
        while (historyStack.size > currentHistoryIndex + 1) {
            // Help GC by recycling bitmaps in the redo stack
            historyStack.removeLast().bitmap.recycle()
        }

        val newItem = HistoryItem(bitmapToSave, action)
        historyStack.add(newItem)
        currentHistoryIndex = historyStack.size - 1

        manageMemoryUsage()
    }

    private fun manageMemoryUsage() {
        var currentUsage = 0L
        // Calculate total usage
        for (item in historyStack) {
            currentUsage += item.bitmap.allocationByteCount
        }

        // Remove oldest until we are under the limit
        while (currentUsage > MAX_RAM_USAGE && historyStack.size > 1) {
            val removed = historyStack.removeFirst()
            currentUsage -= removed.bitmap.allocationByteCount
            removed.bitmap.recycle()
            currentHistoryIndex--
        }

        if (currentHistoryIndex < 0) currentHistoryIndex = 0
    }

    fun undo(): Bitmap? {
        if (currentHistoryIndex > 0) {
            currentHistoryIndex--
            restoreState(historyStack[currentHistoryIndex])
            onUndoAction?.invoke()
            return baseBitmap
        }
        return null
    }

    fun redo(): Bitmap? {
        if (currentHistoryIndex < historyStack.size - 1) {
            currentHistoryIndex++
            restoreState(historyStack[currentHistoryIndex])
            onRedoAction?.invoke()
            return baseBitmap
        }
        return null
    }

    private fun restoreState(item: HistoryItem) {
        // Recycle current view bitmap to save RAM before loading new one
        if (baseBitmap != item.bitmap) {
            baseBitmap?.recycle() 
        }
        
        // Create a copy so we don't mutate the history item if we draw again
        baseBitmap = item.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        // Restore adjustment values if this state had them, or reset if not
        // Note: Since the bitmap is burned in, we reset the live preview values
        // unless we want to edit them again. For simplicity in this "Snapshot" model,
        // we assume the bitmap contains the visual state.
        
        // However, if the last action was Adjust, we might want to restore sliders.
        // For now, we rely on the bitmap visual.
        
        updateImageMatrix()
        invalidate()
    }

    // Bitmap Handling & Proxy System

    fun setBitmap(bitmap: Bitmap?) {
        // Clear old history
        historyStack.forEach { it.bitmap.recycle() }
        historyStack.clear()
        currentHistoryIndex = -1
        baseBitmap?.recycle()
        originalHighResBitmap?.recycle()

        if (bitmap != null) {
            // 1. Save Original High Res
            originalHighResBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

            // 2. Create Proxy (Max 2000px)
            baseBitmap = createProxyBitmap(bitmap)

            // 3. Save Initial State
            saveCurrentState(RestoreAction.None)
            
            // Reset view
            scaleFactor = 1.0f
            translationX = 0f
            translationY = 0f
            updateImageMatrix()
            invalidate()

            post {
                if (currentTool == ToolType.CROP && isCropModeActive) {
                    setCropMode(currentCropMode)
                }
            }
        } else {
            baseBitmap = null
            originalHighResBitmap = null
            invalidate()
        }
    }

    private fun createProxyBitmap(source: Bitmap): Bitmap {
        val maxDimension = 2000
        val ratio = Math.min(
            maxDimension.toFloat() / source.width,
            maxDimension.toFloat() / source.height
        )

        return if (ratio >= 1.0f) {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            val width = (source.width * ratio).toInt()
            val height = (source.height * ratio).toInt()
            Bitmap.createScaledBitmap(source, width, height, true)
        }
    }
    
    // Replay Logic for High Quality Export
    
    fun getFinalBitmap(): Bitmap? {
        val original = originalHighResBitmap ?: return null
        if (historyStack.isEmpty()) return original

        // Start with a copy of the original high-res
        var resultBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        
        // We need to track the size of the image as we apply crops to calculate scale correctly
        var currentWidth = resultBitmap.width.toFloat()
        var currentHeight = resultBitmap.height.toFloat()

        // Replay Loop
        // Skip index 0 (Import) as resultBitmap is already that
        for (i in 1..currentHistoryIndex) {
            val item = historyStack[i]
            
            when (val action = item.action) {
                is RestoreAction.Draw -> {
                    val canvas = Canvas(resultBitmap)
                    // The action stores the proxy width/height at the time of drawing
                    // We need to compare current resultBitmap size vs proxy size to find scale
                    
                    // Since we don't store proxy size in Draw action easily, we rely on the
                    // fact that the Proxy is a scaled version of the CURRENT HighRes state.
                    // But wait, the proxy might have been cropped. 
                    
                    // To be precise: We need the scale relative to the bitmap it was drawn on.
                    // The previous history item contains the bitmap it was drawn ON.
                    val prevProxy = historyStack[i-1].bitmap
                    
                    val scaleX = currentWidth / prevProxy.width
                    val scaleY = currentHeight / prevProxy.height
                    
                    val scaleMatrix = Matrix()
                    scaleMatrix.setScale(scaleX, scaleY)
                    
                    val highResPath = Path()
                    action.path.transform(scaleMatrix, highResPath)
                    
                    val highResPaint = Paint(action.paint)
                    highResPaint.strokeWidth = action.paint.strokeWidth * scaleX
                    
                    canvas.drawPath(highResPath, highResPaint)
                }
                is RestoreAction.Crop -> {
                    // Calculate scale based on the proxy dimensions stored in the action
                    val scaleX = currentWidth / action.canvasWidth
                    val scaleY = currentHeight / action.canvasHeight
                    
                    val highResRect = RectF(
                        action.cropRect.left * scaleX,
                        action.cropRect.top * scaleY,
                        action.cropRect.right * scaleX,
                        action.cropRect.bottom * scaleY
                    )
                    
                    // Ensure valid coords
                    val safeLeft = highResRect.left.coerceIn(0f, currentWidth)
                    val safeTop = highResRect.top.coerceIn(0f, currentHeight)
                    val safeRight = highResRect.right.coerceIn(0f, currentWidth)
                    val safeBottom = highResRect.bottom.coerceIn(0f, currentHeight)
                    
                    if (safeRight > safeLeft && safeBottom > safeTop) {
                         val cropped = Bitmap.createBitmap(
                            resultBitmap,
                            safeLeft.toInt(),
                            safeTop.toInt(),
                            (safeRight - safeLeft).toInt(),
                            (safeBottom - safeTop).toInt()
                        )
                        resultBitmap.recycle() // Free old big bitmap
                        resultBitmap = cropped
                        currentWidth = resultBitmap.width.toFloat()
                        currentHeight = resultBitmap.height.toFloat()
                    }
                }
                is RestoreAction.Adjust -> {
                    val canvas = Canvas(resultBitmap)
                    val paint = Paint()
                    
                    val cm = ColorMatrix()
                    val translation = action.brightness + (1f - action.contrast) * 128f
                    cm.set(floatArrayOf(
                        action.contrast, 0f, 0f, 0f, translation,
                        0f, action.contrast, 0f, 0f, translation,
                        0f, 0f, action.contrast, 0f, translation,
                        0f, 0f, 0f, 1f, 0f
                    ))
                    val satMatrix = ColorMatrix().apply { setSaturation(action.saturation) }
                    cm.postConcat(satMatrix)
                    
                    paint.colorFilter = ColorMatrixColorFilter(cm)
                    
                    // Draw onto itself (create new bitmap to apply filter)
                    val filtered = Bitmap.createBitmap(resultBitmap.width, resultBitmap.height, Bitmap.Config.ARGB_8888)
                    val c = Canvas(filtered)
                    c.drawBitmap(resultBitmap, 0f, 0f, paint)
                    
                    resultBitmap.recycle()
                    resultBitmap = filtered
                }
                is RestoreAction.None -> {}
            }
        }
        
        return resultBitmap
    }

    fun canUndo(): Boolean = currentHistoryIndex > 0
    fun canRedo(): Boolean = currentHistoryIndex < historyStack.size - 1

    // Drawing

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

            if (isSketchMode) {
                canvas.drawColor(Color.WHITE)
            }

            if (it.hasAlpha() && !isSketchMode) {
                checkerDrawable.draw(canvas)
            }

            canvas.drawBitmap(it, imageMatrix, imagePaint)
            canvas.restore()
        }

        currentDrawingTool.onDraw(canvas, paint)

        if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
            drawCropOverlay(canvas)
        }
    }

    // Tool Logic

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

    // Gestures

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
        val screenSpaceAction = currentDrawingTool.onTouchEvent(event, paint)

        screenSpaceAction?.let { action ->
            mergeDrawingStrokeIntoBitmap(action)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> isDrawing = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                // State saved inside mergeDrawingStrokeIntoBitmap for the action
            }
        }

        invalidate()
        return true
    }

    // Crop Logic

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

                when (resizeHandle) {
                    1 -> {
                        touchOffsetX = cropRect.left - x
                        touchOffsetY = cropRect.top - y
                    }
                    2 -> {
                        touchOffsetX = cropRect.right - x
                        touchOffsetY = cropRect.top - y
                    }
                    3 -> {
                        touchOffsetX = cropRect.left - x
                        touchOffsetY = cropRect.bottom - y
                    }
                    4 -> {
                        touchOffsetX = cropRect.right - x
                        touchOffsetY = cropRect.bottom - y
                    }
                }

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
            
            cropStartX = x
            cropStartY = y
            cropStartLeft = cropRect.left
            cropStartTop = cropRect.top
            cropStartRight = cropRect.right
            cropStartBottom = cropRect.bottom
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

    fun applyCrop(): Bitmap? {
        if (baseBitmap == null || cropRect.isEmpty) return null

        val bitmapWithDrawings = baseBitmap ?: return null
        
        // Capture the current dimensions BEFORE cropping for the history replay
        val currentCanvasWidth = bitmapWithDrawings.width
        val currentCanvasHeight = bitmapWithDrawings.height

        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        val imageCropRect = RectF()
        inverseMatrix.mapRect(imageCropRect, cropRect)

        val left = imageCropRect.left.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val top = imageCropRect.top.coerceIn(0f, bitmapWithDrawings.height.toFloat())
        val right = imageCropRect.right.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val bottom = imageCropRect.bottom.coerceIn(0f, bitmapWithDrawings.height.toFloat())

        if (right <= left || bottom <= top) return null

        try {
            val croppedBitmap = Bitmap.createBitmap(
                bitmapWithDrawings,
                left.toInt(),
                top.toInt(),
                (right - left).toInt(),
                (bottom - top).toInt()
            )
            
            // Save logic: Capture the crop rect relative to the Proxy
            val historyCropRect = RectF(left, top, right, bottom)

            baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)

            saveCurrentState(RestoreAction.Crop(historyCropRect, currentCanvasWidth, currentCanvasHeight))

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
        val targetX = x + touchOffsetX
        val targetY = y + touchOffsetY

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
            1 -> Pair(cropRect.right, cropRect.bottom)
            2 -> Pair(cropRect.left, cropRect.bottom)
            3 -> Pair(cropRect.right, cropRect.top)
            4 -> Pair(cropRect.left, cropRect.top)
            else -> Pair(cropRect.left, cropRect.top)
        }
    }

    private fun calculateAspectRatioSize(x: Float, y: Float, fixedX: Float, fixedY: Float, aspectRatio: Float): Pair<Float, Float> {
        val signX = if (resizeHandle == 1 || resizeHandle == 3) -1 else 1
        val signY = if (resizeHandle == 1 || resizeHandle == 2) -1 else 1

        var newWidth = (x - fixedX) * signX
        var newHeight = (y - fixedY) * signY

        newWidth = newWidth.coerceAtLeast(0f)
        newHeight = newHeight.coerceAtLeast(0f)

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
        } else {
            cropRect.right = x
            cropRect.bottom = y
        }
    }

    // Adjustments

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

    fun applyAdjustmentsToBitmap(): Bitmap? {
        if (baseBitmap == null) return null

        val adjustedBitmap = Bitmap.createBitmap(baseBitmap!!.width, baseBitmap!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(adjustedBitmap)
        val paint = Paint().apply { colorFilter = imagePaint.colorFilter }
        canvas.drawBitmap(baseBitmap!!, 0f, 0f, paint)

        baseBitmap = adjustedBitmap
        
        // Save state with the adjustment values so we can replay later
        saveCurrentState(RestoreAction.Adjust(brightness, contrast, saturation))
        
        invalidate()

        return baseBitmap
    }

    fun resetAdjustments() {
        setAdjustments(0f, 1f, 1f)
    }

    // Helpers

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
        val drawingBitmap = getFinalBitmap() ?: return null
        val whiteBitmap = Bitmap.createBitmap(drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whiteBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(drawingBitmap, 0f, 0f, null)
        return whiteBitmap
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

        val canvas = Canvas(baseBitmap!!)
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        canvas.concat(inverseMatrix)
        
        // Apply to the Proxy Bitmap
        canvas.drawPath(action.path, action.paint)
        
        // Save the Proxy State AND the raw action data for High Res replay
        // Note: The path here is in SCREEN coordinates. 
        // When replaying on High Res, we will scale it based on the ratio of Proxy vs High Res.
        saveCurrentState(RestoreAction.Draw(Path(action.path), Paint(action.paint)))

        invalidate()
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