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

    private var baseBitmap: Bitmap? = null
    private var originalHighResBitmap: Bitmap? = null
    
    private val imageMatrix = android.graphics.Matrix()
    private val imageBounds = RectF()

    // Internal Action Data for High-Res Export Replay
    private data class ActionData(
        val type: ActionType,
        val path: Path? = null,
        val paint: Paint? = null,
        val cropRect: RectF? = null,
        val canvasWidth: Int = 0,
        val canvasHeight: Int = 0,
        val brightness: Float = 0f,
        val contrast: Float = 1f,
        val saturation: Float = 1f
    )

    private enum class ActionType {
        IMPORT, DRAW, CROP, ADJUST
    }

    // RAM-based history
    private val bitmapHistory = LinkedList<Bitmap>()
    private val actionHistory = LinkedList<ActionData>()
    
    private var currentHistoryIndex = -1
    private var savedHistoryIndex = -1
    
    // 500MB Limit Implementation
    private val MAX_RAM_USAGE = 500 * 1024 * 1024L
    private val PROXY_MAX_SIZE = 1500

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
    // Note: We mostly ignore this now to prevent ViewModel loops, but kept for compilation
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

    fun markAsSaved() {
        savedHistoryIndex = currentHistoryIndex
    }

    fun hasUnsavedChanges(): Boolean {
        return savedHistoryIndex != currentHistoryIndex
    }

    private fun saveCurrentState(action: ActionData) {
        val currentBitmap = baseBitmap ?: return
        if (currentBitmap.isRecycled) return

        // Create Snapshot
        val snapshot = try {
            currentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: OutOfMemoryError) {
            System.gc()
            return
        }

        saveSnapshotToHistory(snapshot, action)
    }

    private fun saveSnapshotToHistory(snapshot: Bitmap, action: ActionData) {
        // Clear redo history
        while (bitmapHistory.size > currentHistoryIndex + 1) {
            val removed = bitmapHistory.removeLast()
            if (removed != baseBitmap && !removed.isRecycled) {
                removed.recycle()
            }
            actionHistory.removeLast()
        }

        bitmapHistory.add(snapshot)
        actionHistory.add(action)
        currentHistoryIndex++

        manageMemoryUsage()
    }

    private fun manageMemoryUsage() {
        var currentSize: Long = 0
        // Calculate total size
        for (bmp in bitmapHistory) {
            if (!bmp.isRecycled) {
                currentSize += bmp.allocationByteCount
            }
        }
        
        // Remove oldest if over 500MB limit (keeping at least 1 undo)
        while (currentSize > MAX_RAM_USAGE && bitmapHistory.size > 1) {
            val removed = bitmapHistory.removeFirst()
            if (!removed.isRecycled) {
                currentSize -= removed.allocationByteCount
                if (removed != baseBitmap) removed.recycle()
            }
            actionHistory.removeFirst()
            currentHistoryIndex--
            savedHistoryIndex--
        }

        if (currentHistoryIndex < 0) currentHistoryIndex = 0
        if (savedHistoryIndex < -1) savedHistoryIndex = -1
    }

    fun undo(): Bitmap? {
        if (currentHistoryIndex > 0) {
            currentHistoryIndex--
            restoreBitmapFromHistory(currentHistoryIndex)
            onUndoAction?.invoke()
            return baseBitmap
        }
        return null
    }

    fun redo(): Bitmap? {
        if (currentHistoryIndex < bitmapHistory.size - 1) {
            currentHistoryIndex++
            restoreBitmapFromHistory(currentHistoryIndex)
            onRedoAction?.invoke()
            return baseBitmap
        }
        return null
    }

    private fun restoreBitmapFromHistory(index: Int) {
        if (index < 0 || index >= bitmapHistory.size) return
        
        // Simply restore the bitmap from history
        val historyBitmap = bitmapHistory[index]
        if (!historyBitmap.isRecycled) {
            baseBitmap?.recycle()
            baseBitmap = historyBitmap.copy(Bitmap.Config.ARGB_8888, true)
            updateImageMatrix()
            invalidate()
        }
    }

    fun setBitmap(bitmap: Bitmap?) {
        // Clean up
        bitmapHistory.forEach { if (!it.isRecycled) it.recycle() }
        bitmapHistory.clear()
        actionHistory.clear()
        currentHistoryIndex = -1
        savedHistoryIndex = -1
        baseBitmap = null
        originalHighResBitmap?.recycle()

        if (bitmap != null) {
            val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            originalHighResBitmap = safeBitmap

            // Create Proxy
            baseBitmap = createProxyBitmap(safeBitmap)
            
            // Initial State
            val initialAction = ActionData(ActionType.IMPORT)
            val initialSnapshot = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
            
            bitmapHistory.add(initialSnapshot)
            actionHistory.add(initialAction)
            currentHistoryIndex = 0
            savedHistoryIndex = 0
            
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
            invalidate()
        }
    }

    private fun createProxyBitmap(source: Bitmap): Bitmap {
        val ratio = Math.min(
            PROXY_MAX_SIZE.toFloat() / source.width,
            PROXY_MAX_SIZE.toFloat() / source.height
        )

        val width: Int
        val height: Int
        
        if (ratio >= 1.0f) {
            width = source.width
            height = source.height
        } else {
            width = (source.width * ratio).toInt()
            height = (source.height * ratio).toInt()
        }
        
        val mutableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mutableBitmap)
        canvas.drawBitmap(source, null, Rect(0, 0, width, height), null)
        
        return mutableBitmap
    }
    
    fun getFinalBitmap(): Bitmap? {
        val original = originalHighResBitmap ?: return null
        if (actionHistory.isEmpty()) return original.copy(Bitmap.Config.ARGB_8888, true)

        var resultBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        
        var currentWidth = resultBitmap.width.toFloat()
        var currentHeight = resultBitmap.height.toFloat()

        for (i in 1..currentHistoryIndex) {
            val action = actionHistory[i]
            
            when (action.type) {
                ActionType.DRAW -> {
                    if (action.path != null && action.paint != null) {
                        val canvas = Canvas(resultBitmap)
                        val prevProxy = bitmapHistory[i-1]
                        
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
                }
                ActionType.CROP -> {
                    if (action.cropRect != null) {
                        val scaleX = currentWidth / action.canvasWidth
                        val scaleY = currentHeight / action.canvasHeight
                        
                        val highResRect = RectF(
                            action.cropRect.left * scaleX,
                            action.cropRect.top * scaleY,
                            action.cropRect.right * scaleX,
                            action.cropRect.bottom * scaleY
                        )
                        
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
                            resultBitmap.recycle()
                            resultBitmap = cropped
                            currentWidth = resultBitmap.width.toFloat()
                            currentHeight = resultBitmap.height.toFloat()
                        }
                    }
                }
                ActionType.ADJUST -> {
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
                    
                    val filtered = Bitmap.createBitmap(resultBitmap.width, resultBitmap.height, Bitmap.Config.ARGB_8888)
                    val c = Canvas(filtered)
                    c.drawBitmap(resultBitmap, 0f, 0f, paint)
                    
                    resultBitmap.recycle()
                    resultBitmap = filtered
                }
                ActionType.IMPORT -> {}
            }
        }
        
        return resultBitmap
    }

    fun canUndo(): Boolean = currentHistoryIndex > 0
    fun canRedo(): Boolean = currentHistoryIndex < bitmapHistory.size - 1

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

        // DrawingTool returns non-null ONLY when stroke is completed (ACTION_UP)
        screenSpaceAction?.let { action ->
            // Instead of replaying, save individual bitmap snapshots
            saveIndividualDrawingState(action)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
            }
        }

        invalidate()
        return true
    }

    private fun saveIndividualDrawingState(action: DrawingAction) {
        // Create a new bitmap with the current stroke added
        val currentBitmap = baseBitmap ?: return
        
        // Create a copy of current bitmap
        val newBitmap = currentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(newBitmap)
        
        // Apply the stroke to the new bitmap
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        canvas.concat(inverseMatrix)
        canvas.drawPath(action.path, action.paint)
        
        // Update baseBitmap to the new version
        baseBitmap?.recycle()
        baseBitmap = newBitmap
        
        // Save snapshot to history for undo/redo
        val snapshot = newBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val actionData = ActionData(
            ActionType.DRAW,
            path = Path(action.path), 
            paint = Paint(action.paint)
        )
        saveSnapshotToHistory(snapshot, actionData)
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

            baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
            
            val historyCropRect = RectF(left, top, right, bottom)
            val actionData = ActionData(
                ActionType.CROP,
                cropRect = historyCropRect,
                canvasWidth = currentCanvasWidth,
                canvasHeight = currentCanvasHeight
            )
            saveCurrentState(actionData)

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

        val actionData = ActionData(
            ActionType.ADJUST,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation
        )
        saveCurrentState(actionData)
        
        invalidate()

        return baseBitmap
    }

    fun resetAdjustments() {
        setAdjustments(0f, 1f, 1f)
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
        
        canvas.drawPath(action.path, action.paint)

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