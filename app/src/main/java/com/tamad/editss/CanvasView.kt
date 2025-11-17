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
    
    private val sketchStrokes = mutableListOf<DrawingAction>()
    private val undoneSketchStrokes = mutableListOf<DrawingAction>()
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
    var onUndoAction: ((EditAction) -> Unit)? = null // Callback for undo operations
    var onRedoAction: ((EditAction) -> Unit)? = null // Callback for redo operations
    var onBitmapChanged: ((EditAction.BitmapChange) -> Unit)? = null // Callback for bitmap changes (drawing and crop)

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
        background = resources.getDrawable(R.drawable.outer_bounds, null)

        updateImageMatrix()
        invalidate()
        post {
            if (currentTool == ToolType.CROP && isCropModeActive) {
                setCropMode(currentCropMode)
            }
        }
    }

    fun setSketchMode(isSketch: Boolean) {
        this.isSketchMode = isSketch
        if (!isSketch) {
            sketchStrokes.clear()
        }
        invalidate()
    }

    fun getBaseBitmap(): Bitmap? = baseBitmap

    fun handleCropUndo(cropAction: CropAction) {
        baseBitmap = cropAction.previousBitmap.copy(Bitmap.Config.ARGB_8888, true)
        updateImageMatrix()
        cropRect.setEmpty()
        invalidate()
    }

    fun handleCropRedo(cropAction: CropAction) {
        // This is handled by EditAction.BitmapChange
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

    fun handleBitmapChangeUndo(action: EditAction.BitmapChange) {
        baseBitmap = action.previousBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (isSketchMode && action.associatedStroke != null) {
            undoneSketchStrokes.add(action.associatedStroke)
            sketchStrokes.remove(action.associatedStroke)
        }
        updateImageMatrix()
        invalidate()
    }

    fun handleBitmapChangeRedo(action: EditAction.BitmapChange) {
        baseBitmap = action.newBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (isSketchMode && action.associatedStroke != null) {
            sketchStrokes.add(action.associatedStroke)
            undoneSketchStrokes.remove(action.associatedStroke)
        }
        updateImageMatrix()
        invalidate()
    }

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

        val bitmapWithDrawings = baseBitmap ?: return null
        val previousBaseBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true)

        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        val imageCropRect = RectF()
        inverseMatrix.mapRect(imageCropRect, cropRect)

        val left = imageCropRect.left.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val top = imageCropRect.top.coerceIn(0f, bitmapWithDrawings.height.toFloat())
        val right = imageCropRect.right.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val bottom = imageCropRect.bottom.coerceIn(0f, bitmapWithDrawings.height.toFloat())

        if (right <= left || bottom <= top) return null

        if (isSketchMode) {
            val translationMatrix = Matrix()
            translationMatrix.postTranslate(-left, -top)

            val updatedStrokes = sketchStrokes.map { stroke ->
                val newPath = Path()
                stroke.path.transform(translationMatrix, newPath)
                DrawingAction(newPath, stroke.paint)
            }
            sketchStrokes.clear()
            sketchStrokes.addAll(updatedStrokes)
            
            val updatedUndoneStrokes = undoneSketchStrokes.map { stroke ->
                 val newPath = Path()
                 stroke.path.transform(translationMatrix, newPath)
                 DrawingAction(newPath, stroke.paint)
            }
            undoneSketchStrokes.clear()
            undoneSketchStrokes.addAll(updatedUndoneStrokes)
        }

        val croppedBitmap = Bitmap.createBitmap(
            bitmapWithDrawings,
            left.toInt(),
            top.toInt(),
            (right - left).toInt(),
            (bottom - top).toInt()
        )

        baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        onBitmapChanged?.invoke(EditAction.BitmapChange(
            previousBitmap = previousBaseBitmap,
            newBitmap = baseBitmap!!
        ))

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
        if (isSketchMode) {
            baseBitmap?.let { currentBitmap ->
                val transparentBitmap = Bitmap.createBitmap(
                    currentBitmap.width,
                    currentBitmap.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(transparentBitmap)
                val tempPaint = Paint()
                for (stroke in sketchStrokes) {
                    tempPaint.set(stroke.paint)
                    // Apply the same color filter used for display
                    tempPaint.colorFilter = imagePaint.colorFilter
                    canvas.drawPath(stroke.path, tempPaint)
                }
                return transparentBitmap
            }
        }
        return getDrawingOnTransparent()
    }
    
    fun getSketchDrawingOnWhite(): Bitmap? {
        return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
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
        val canvas = Canvas(baseBitmap!!)
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        canvas.concat(inverseMatrix)
        canvas.drawPath(action.path, action.paint)
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
            if (it.hasAlpha()) {
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

        if (isZooming || event.pointerCount > 1) {
            if (isDrawing && currentTool == ToolType.DRAW) {
                currentDrawingTool.onTouchEvent(MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_CANCEL, event.x, event.y, 0), paint)
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
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        lastFocusX = 0f
                        lastFocusY = 0f
                    }
                }
            }
            return true
        }

        val x = event.x
        val y = event.y

        if (currentTool == ToolType.DRAW) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> isDrawing = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDrawing = false
            }
            
            val screenSpaceAction = currentDrawingTool.onTouchEvent(event, paint)
            screenSpaceAction?.let { action ->
                val bitmapBeforeDrawing = baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                mergeDrawingStrokeIntoBitmap(action)

                var finalAssociatedStroke: DrawingAction? = null

                // For BOTH sketch mode AND imported images: merge strokes into baseBitmap
                val inverseMatrix = Matrix()
                imageMatrix.invert(inverseMatrix)
                
                val bitmapSpacePaint = Paint(paint)
                val bitmapStrokeWidth = inverseMatrix.mapRadius(paint.strokeWidth)
                bitmapSpacePaint.strokeWidth = bitmapStrokeWidth
                
                val bitmapPath = Path()
                action.path.transform(inverseMatrix, bitmapPath)
                
                val bitmapSpaceAction = DrawingAction(bitmapPath, bitmapSpacePaint)
                sketchStrokes.add(bitmapSpaceAction)
                undoneSketchStrokes.clear()
                finalAssociatedStroke = bitmapSpaceAction

                if (bitmapBeforeDrawing != null) {
                    onBitmapChanged?.invoke(EditAction.BitmapChange(
                        previousBitmap = bitmapBeforeDrawing,
                        newBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true),
                        associatedStroke = finalAssociatedStroke
                    ))
                }
            }
            invalidate()
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                if (currentTool == ToolType.CROP) {
                    if (!cropRect.isEmpty) {
                        resizeHandle = getResizeHandle(x, y)
                        if (resizeHandle > 0) {
                            isResizingCropRect = true
                            cropStartLeft = cropRect.left; cropStartTop = cropRect.top
                            cropStartRight = cropRect.right; cropStartBottom = cropRect.bottom
                            return true
                        }
                    }
                    if (cropRect.contains(x, y)) {
                        isMovingCropRect = true
                        cropStartX = x; cropStartY = y
                        cropStartLeft = cropRect.left; cropStartTop = cropRect.top
                        cropStartRight = cropRect.right; cropStartBottom = cropRect.bottom
                        return true
                    }
                    if (cropRect.isEmpty && isCropModeActive) {
                        isCropping = true
                        cropRect.set(x, y, x, y)
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentTool == ToolType.CROP) {
                    if (isResizingCropRect) {
                        resizeCropRect(x, y)
                        clampCropRectToBounds()
                        invalidate()
                    } else if (isMovingCropRect) {
                        var dx = x - cropStartX
                        var dy = y - cropStartY
                        val visibleBounds = getVisibleImageBounds()
                        if (cropStartLeft + dx < visibleBounds.left) dx = visibleBounds.left - cropStartLeft
                        if (cropStartTop + dy < visibleBounds.top) dy = visibleBounds.top - cropStartTop
                        if (cropStartRight + dx > visibleBounds.right) dx = visibleBounds.right - cropStartRight
                        if (cropStartBottom + dy > visibleBounds.bottom) dy = visibleBounds.bottom - cropStartBottom
                        cropRect.set(cropStartLeft + dx, cropStartTop + dy, cropStartRight + dx, cropStartBottom + dy)
                        clampCropRectToBounds()
                        invalidate()
                    } else if (isCropping) {
                        updateCropRect(x, y)
                        clampCropRectToBounds()
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (currentTool == ToolType.CROP) {
                    if(isCropping) enforceAspectRatio()
                    isCropping = false
                    isMovingCropRect = false
                    isResizingCropRect = false
                    resizeHandle = 0
                }
                return true
            }
        }
        return false
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

    private fun getResizeHandle(x: Float, y: Float): Int {
        val cornerSize = 60f
        if (RectF(cropRect.left - cornerSize, cropRect.top - cornerSize, cropRect.left + cornerSize, cropRect.top + cornerSize).contains(x, y)) return 1
        if (RectF(cropRect.right - cornerSize, cropRect.top - cornerSize, cropRect.right + cornerSize, cropRect.top + cornerSize).contains(x, y)) return 2
        if (RectF(cropRect.left - cornerSize, cropRect.bottom - cornerSize, cropRect.left + cornerSize, cropRect.bottom + cornerSize).contains(x, y)) return 3
        if (RectF(cropRect.right - cornerSize, cropRect.bottom - cornerSize, cropRect.right + cornerSize, cropRect.bottom + cornerSize).contains(x, y)) return 4
        return 0
    }

    private fun resizeCropRect(x: Float, y: Float) {
        val minSize = 50f
        var newLeft = cropRect.left; var newTop = cropRect.top
        var newRight = cropRect.right; var newBottom = cropRect.bottom

        when (resizeHandle) {
            1 -> { newLeft = x.coerceAtMost(newRight - minSize); newTop = y.coerceAtMost(newBottom - minSize) }
            2 -> { newRight = x.coerceAtLeast(newLeft + minSize); newTop = y.coerceAtMost(newBottom - minSize) }
            3 -> { newLeft = x.coerceAtMost(newRight - minSize); newBottom = y.coerceAtLeast(newTop + minSize) }
            4 -> { newRight = x.coerceAtLeast(newLeft + minSize); newBottom = y.coerceAtLeast(newTop + minSize) }
        }

        val aspectRatio = when (currentCropMode) {
            CropMode.SQUARE -> 1f
            CropMode.PORTRAIT -> 9f / 16f
            CropMode.LANDSCAPE -> 16f / 9f
            else -> 0f
        }

        if (aspectRatio > 0) {
            val (fixedX, fixedY) = when (resizeHandle) {
                1 -> Pair(cropRect.right, cropRect.bottom)
                2 -> Pair(cropRect.left, cropRect.bottom)
                3 -> Pair(cropRect.right, cropRect.top)
                4 -> Pair(cropRect.left, cropRect.top)
                else -> return
            }

            var newWidth = Math.abs(x - fixedX)
            var newHeight = Math.abs(y - fixedY)

            if (newWidth / newHeight > aspectRatio) {
                newWidth = newHeight * aspectRatio
            } else {
                newHeight = newWidth / aspectRatio
            }
            
            when (resizeHandle) {
                1 -> { newLeft = fixedX - newWidth; newTop = fixedY - newHeight }
                2 -> { newRight = fixedX + newWidth; newTop = fixedY - newHeight }
                3 -> { newLeft = fixedX - newWidth; newBottom = fixedY + newHeight }
                4 -> { newRight = fixedX + newWidth; newBottom = fixedY + newHeight }
            }
        }
        cropRect.set(newLeft, newTop, newRight, newBottom)
    }

    private fun updateCropRect(x: Float, y: Float) {
        cropRect.right = Math.max(x, cropRect.left)
        cropRect.bottom = Math.max(y, cropRect.top)
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
        
        // Apply brightness/contrast while preserving alpha
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
        
        // Ensure proper PorterDuff mode for transparent images
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
        // For sketch mode with applied adjustments:
        // Takes baseBitmap (which has white bg + strokes + adjustments)
        // and converts white background to transparent
        baseBitmap?.let { currentBitmap ->
            val transparentBitmap = Bitmap.createBitmap(
                currentBitmap.width,
                currentBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            
            val pixels = IntArray(currentBitmap.width * currentBitmap.height)
            currentBitmap.getPixels(pixels, 0, currentBitmap.width, 0, 0, currentBitmap.width, currentBitmap.height)
            
            // Convert near-white pixels to transparent
            // Only remove pixels that are VERY white (all RGB > 250) to preserve anti-aliased edges
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val alpha = (pixel shr 24) and 0xFF
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                
                // Only if pixel is nearly pure white AND has full opacity, make it transparent
                // This preserves the anti-aliased stroke edges (which are gray/semi-transparent)
                if (red > 250 && green > 250 && blue > 250 && alpha == 255) {
                    pixels[i] = 0x00000000 // Transparent
                }
            }
            
            transparentBitmap.setPixels(pixels, 0, currentBitmap.width, 0, 0, currentBitmap.width, currentBitmap.height)
            return transparentBitmap
        }
        return null
    }
}