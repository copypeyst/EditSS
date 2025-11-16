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
            // Only allow scaling if we had multiple touch points previously
            if (this@CanvasView.lastPointerCount < 2) {
                return false
            }
            
            val oldScaleFactor = scaleFactor
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f) // Limit zoom out to 1.0x and zoom in to 5.0x

            // Transform focus point to image coordinates to anchor zoom properly
            val inverseMatrix = Matrix()
            imageMatrix.invert(inverseMatrix)
            val focusPoint = floatArrayOf(detector.focusX, detector.focusY)
            val imagePoint = floatArrayOf(0f, 0f)
            inverseMatrix.mapPoints(imagePoint, focusPoint)

            // Update image matrix with new scale
            updateImageMatrix()

            // Transform back to screen coordinates to find where the image point should be
            val screenPoint = floatArrayOf(0f, 0f)
            imageMatrix.mapPoints(screenPoint, imagePoint)

            // Adjust translation so the image point stays under the focus
            translationX += detector.focusX - screenPoint[0]
            translationY += detector.focusY - screenPoint[1]

            // Update image matrix with adjusted translation
            updateImageMatrix()
            invalidate()
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Only allow scaling to begin if we detected multiple touch points
            if (this@CanvasView.lastPointerCount < 2) {
                return false
            }
            
            isZooming = true
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isZooming = false
            // Only reset zoom and translation when zoomed out to default
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
    
    // Track drawing strokes for sketch mode transparency support
    private val sketchStrokes = mutableListOf<DrawingAction>()
    private val undoneSketchStrokes = mutableListOf<DrawingAction>()
    private var isSketchMode = false // Track if we're in sketch mode (no imported/captured image)

    // Gesture detection
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
    var onCropAction: ((CropAction) -> Unit)? = null // Legacy callback
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

        // Re-initialize crop rectangle after image is set
        // Only if crop tool is currently selected and a crop mode is active
        post {
            if (currentTool == ToolType.CROP && isCropModeActive) {
                setCropMode(currentCropMode)
            }
        }
    }

    fun setPaths(paths: List<DrawingAction>) {
        // Legacy method kept for compatibility - no longer used with bitmap-based drawing
        invalidate()
    }

    fun setSketchMode(isSketch: Boolean) {
        this.isSketchMode = isSketch
        if (!isSketch) {
            // Clear sketch strokes when leaving sketch mode
            sketchStrokes.clear()
        }
        invalidate()
    }

    fun getBaseBitmap(): Bitmap? = baseBitmap

    // Handle undo/redo operations for the unified action system
    fun handleUndo(actions: List<EditAction>) {
        // For bitmap-based drawing system, undo/redo is handled by EditAction.BitmapChange
        // No need to manage separate paths anymore
        invalidate()
    }

    fun handleRedo(actions: List<EditAction>) {
        // For bitmap-based drawing system, undo/redo is handled by EditAction.BitmapChange
        // No need to manage separate paths anymore
        invalidate()
    }

    // Handle crop undo - restore previous bitmap state
    fun handleCropUndo(cropAction: CropAction) {
        // Restore the bitmap to the state before the crop.
        baseBitmap = cropAction.previousBitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        // Drawings are already merged into bitmap, no paths to clear
        
        // Update the image matrix to properly display the restored bitmap.
        updateImageMatrix()
        
        // Clear any existing crop rectangle.
        cropRect.setEmpty()
        
        // Redraw the canvas.
        invalidate()
    }


    fun handleCropRedo(cropAction: CropAction) {
        // This function should ideally restore the bitmap to the state *after* the crop.
        // The actual redo of a crop is handled by EditAction.BitmapChange.
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
            // Only initialize crop rect if a crop mode is already active
            if (isCropModeActive) {
                initializeDefaultCropRect()
            }
        } else {
            // When leaving crop mode, reset crop mode active state
            isCropModeActive = false
        }
        invalidate()
    }

    fun setCropMode(cropMode: CropMode) {
        this.currentCropMode = cropMode
        this.isCropModeActive = true // Mark that a crop mode is now active
        
        // Clear existing crop rectangle to ensure proper recalculation
        cropRect.setEmpty()
        
        if (currentTool == ToolType.CROP) {
            initializeDefaultCropRect()
        }
        invalidate()
    }
    
    fun setCropModeInactive() {
        this.isCropModeActive = false
        if (currentTool == ToolType.CROP) {
            // Clear the crop rectangle when leaving crop mode
            cropRect.setEmpty()
        }
        invalidate()
    }

    private fun resetCropRect() {
        cropRect.setEmpty()
    }

    private fun initializeDefaultCropRect() {
        // Initialize crop rectangle to fit the current visible area with the correct aspect ratio
        if (imageBounds.width() > 0 && imageBounds.height() > 0) {
            // Use visible bounds for proper initialization (accounts for zoom)
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
                        val minSize = 50f
                        if (width < minSize) {
                            width = minSize
                            height = width * 16 / 9f
                        }
                        if (height < minSize) {
                            height = minSize
                            width = height * 9 / 16f
                        }
                    }
                    CropMode.LANDSCAPE -> {
                        width = visibleBounds.width()
                        height = width * 9 / 16f
                        if (height > visibleBounds.height()) {
                            height = visibleBounds.height()
                            width = height * 16 / 9f
                        }
                        val minSize = 50f
                        if (width < minSize) {
                            width = minSize
                            height = width * 9 / 16f
                        }
                        if (height < minSize) {
                            height = minSize
                            width = height * 16 / 9f
                        }
                    }
                }

                // Center the rectangle in the visible area
                val centerX = (visibleBounds.left + visibleBounds.right) / 2
                val centerY = (visibleBounds.top + visibleBounds.bottom) / 2

                cropRect.left = centerX - width / 2
                cropRect.top = centerY - height / 2
                cropRect.right = centerX + width / 2
                cropRect.bottom = centerY + height / 2

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

    private fun clampCropRectToVisibleImage() {
        if (imageBounds.width() <= 0) return

        val visibleBounds = getVisibleImageBounds()
        
        cropRect.left = cropRect.left.coerceIn(visibleBounds.left, visibleBounds.right)
        cropRect.top = cropRect.top.coerceIn(visibleBounds.top, visibleBounds.bottom)
        cropRect.right = cropRect.right.coerceIn(visibleBounds.left, visibleBounds.right)
        cropRect.bottom = cropRect.bottom.coerceIn(visibleBounds.top, visibleBounds.bottom)
    }

    private fun clampCropRectToBounds() {
        clampCropRectToVisibleImage()
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

        // Map crop rectangle from screen coordinates to image coordinates.
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        val imageCropRect = RectF()
        inverseMatrix.mapRect(imageCropRect, cropRect)

        // Clamp to the bounds of the bitmap with drawings.
        val left = imageCropRect.left.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val top = imageCropRect.top.coerceIn(0f, bitmapWithDrawings.height.toFloat())
        val right = imageCropRect.right.coerceIn(0f, bitmapWithDrawings.width.toFloat())
        val bottom = imageCropRect.bottom.coerceIn(0f, bitmapWithDrawings.height.toFloat())

        if (right <= left || bottom <= top) return null

        // --- START OF RESTORED FIX ---
        // If in sketch mode, transform the coordinates of every stored path.
        // The paths are stored relative to the bitmap, so when we crop the bitmap,
        // we must also shift the paths to align with the new, smaller bitmap.
        if (isSketchMode) {
            val translationMatrix = Matrix()
            // Create a matrix that will shift every path up and to the left by the crop amount.
            translationMatrix.postTranslate(-left, -top)

            val updatedStrokes = mutableListOf<DrawingAction>()
            for (stroke in sketchStrokes) {
                val newPath = Path()
                stroke.path.transform(translationMatrix, newPath)
                updatedStrokes.add(DrawingAction(newPath, stroke.paint))
            }
            sketchStrokes.clear()
            sketchStrokes.addAll(updatedStrokes)
            
            // Also transform the undone strokes stack to prevent bugs with undo/redo
            val updatedUndoneStrokes = mutableListOf<DrawingAction>()
            for (stroke in undoneSketchStrokes) {
                 val newPath = Path()
                 stroke.path.transform(translationMatrix, newPath)
                 updatedUndoneStrokes.add(DrawingAction(newPath, stroke.paint))
            }
            undoneSketchStrokes.clear()
            undoneSketchStrokes.addAll(updatedUndoneStrokes)
        }
        // --- END OF RESTORED FIX ---

        // Perform the crop on the bitmap that includes the drawings.
        val croppedBitmap = Bitmap.createBitmap(
            bitmapWithDrawings,
            left.toInt(),
            top.toInt(),
            (right - left).toInt(),
            (bottom - top).toInt()
        )

        // The new base bitmap is the result of the crop.
        baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        val bitmapChangeAction = EditAction.BitmapChange(
            previousBitmap = previousBaseBitmap,
            newBitmap = baseBitmap!!
        )
        onBitmapChanged?.invoke(bitmapChangeAction)

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
            // In sketch mode, we rebuild the drawing from our list of strokes.
            // All strokes are now in the correct coordinate space relative to the current bitmap.
            baseBitmap?.let { currentBitmap ->
                val transparentBitmap = Bitmap.createBitmap(
                    currentBitmap.width,
                    currentBitmap.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(transparentBitmap)
                
                // No special matrix is needed. Just draw the paths directly.
                val tempPaint = Paint()
                for (stroke in sketchStrokes) {
                    tempPaint.set(stroke.paint)
                    canvas.drawPath(stroke.path, tempPaint)
                }
                
                return transparentBitmap
            }
        }
        // Non-sketch mode: use original method
        return getDrawingOnTransparent()
    }
    
    fun getSketchDrawingOnWhite(): Bitmap? {
        if (isSketchMode) {
            // The baseBitmap is always the most up-to-date representation.
            // For a white background drawing, just return a copy of it.
            return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
        }
        // Non-sketch mode: use original method
        return getDrawing()
    }

    fun getFinalBitmap(): Bitmap? {
        return baseBitmap
    }
    
    fun convertTransparentToWhite(bitmap: Bitmap): Bitmap {
        val whiteBitmap = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(whiteBitmap)
        val paint = Paint()
        canvas.drawColor(Color.WHITE)
        paint.isAntiAlias = true
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
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
                val rect = android.graphics.Rect()
                imageBounds.roundOut(rect)
                checker.bounds = rect
                checker.draw(canvas)
            }
            canvas.drawBitmap(it, imageMatrix, imagePaint)
        }

        currentDrawingTool.onDraw(canvas, paint)

        if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
            drawCropOverlay(canvas)
        }

        baseBitmap?.let {
            canvas.restore()
        }
    }

    private fun drawCropOverlay(canvas: Canvas) {
        val overlayPaint = Paint().apply {
            color = Color.BLACK
            alpha = 128
            isAntiAlias = false
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

            val baseScale: Float
            var baseDx = 0f
            var baseDy = 0f

            if (bitmapWidth / viewWidth > bitmapHeight / viewHeight) {
                baseScale = viewWidth / bitmapWidth
                baseDy = (viewHeight - bitmapHeight * baseScale) * 0.5f
            } else {
                baseScale = viewHeight / bitmapHeight
                baseDx = (viewWidth - bitmapWidth * baseScale) * 0.5f
            }

            imageMatrix.setScale(baseScale * scaleFactor, baseScale * scaleFactor)
            imageMatrix.postTranslate(baseDx + translationX, baseDy + translationY)

            imageBounds.set(0f, 0f, bitmapWidth, bitmapHeight)
            imageMatrix.mapRect(imageBounds)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastPointerCount = event.pointerCount
        scaleGestureDetector.onTouchEvent(event)

        val x = event.x
        val y = event.y

        if (event.pointerCount > 1 && isDrawing && currentTool == ToolType.DRAW) {
            currentDrawingTool.onTouchEvent(MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_CANCEL, event.x, event.y, 0), paint)
            isDrawing = false
            invalidate()
            return true
        }

        if (event.pointerCount > 1 && currentTool == ToolType.CROP && (isMovingCropRect || isResizingCropRect)) {
            isMovingCropRect = false
            isResizingCropRect = false
            resizeHandle = 0
            invalidate()
            return true
        }

        if (isZooming || event.pointerCount > 1) {
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

        if (currentTool == ToolType.DRAW) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> isDrawing = true
                MotionEvent.ACTION_UP -> isDrawing = false
                MotionEvent.ACTION_CANCEL -> isDrawing = false
            }
            
            val screenSpaceAction = currentDrawingTool.onTouchEvent(event, paint)
            screenSpaceAction?.let { action ->
                val bitmapBeforeDrawing = baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                
                // 1. Visually update the baseBitmap by drawing the screen-space path onto it.
                mergeDrawingStrokeIntoBitmap(action)

                // 2. For the permanent record (undo stack and transparent export),
                //    convert the path to bitmap coordinates and store that version.
                var bitmapSpaceAction: DrawingAction? = null
                if (isSketchMode) {
                    val bitmapPath = Path()
                    val inverseMatrix = Matrix()
                    imageMatrix.invert(inverseMatrix)
                    action.path.transform(inverseMatrix, bitmapPath)
                    
                    bitmapSpaceAction = DrawingAction(bitmapPath, action.paint)
                    sketchStrokes.add(bitmapSpaceAction)
                    undoneSketchStrokes.clear() // Clear redo stack on new action
                }

                // 3. Report the change, associating the bitmap-space stroke for undo/redo.
                if (bitmapBeforeDrawing != null) {
                    onBitmapChanged?.invoke(EditAction.BitmapChange(
                        previousBitmap = bitmapBeforeDrawing,
                        newBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true),
                        associatedStroke = if (isSketchMode) bitmapSpaceAction else null
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
                        enforceAspectRatio()
                        invalidate()
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
                    if (!isCropModeActive) {
                        return true
                    }
                    isCropping = true
                    cropRect.left = x
                    cropRect.top = y
                    cropRect.right = x
                    cropRect.bottom = y
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
    
                        val newLeft = cropStartLeft + dx
                        val newTop = cropStartTop + dy
                        val newRight = cropStartRight + dx
                        val newBottom = cropStartBottom + dy
    
                        val visibleBounds = getVisibleImageBounds()
    
                        if (newLeft < visibleBounds.left) dx = visibleBounds.left - cropStartLeft
                        if (newTop < visibleBounds.top) dy = visibleBounds.top - cropStartTop
                        if (newRight > visibleBounds.right) dx = visibleBounds.right - cropStartRight
                        if (newBottom > visibleBounds.bottom) dy = visibleBounds.bottom - cropStartBottom
    
                        cropRect.left = cropStartLeft + dx
                        cropRect.top = cropStartTop + dy
                        cropRect.right = cropStartRight + dx
                        cropRect.bottom = cropStartBottom + dy
    
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

    private fun enforceAspectRatio() {
        if (currentCropMode == CropMode.FREEFORM) return

        val targetAspectRatio = when (currentCropMode) {
            CropMode.SQUARE -> 1f
            CropMode.PORTRAIT -> 9f / 16f
            CropMode.LANDSCAPE -> 16f / 9f
            else -> 0f
        }
        if (targetAspectRatio <= 0f) return

        val visibleBounds = getVisibleImageBounds()
        val isInsideBounds = visibleBounds.contains(cropRect)

        val currentWidth = cropRect.width()
        val currentHeight = cropRect.height()
        var isAspectRatioCorrect = false
        if (currentWidth > 0 && currentHeight > 0) {
            val currentRatio = currentWidth / currentHeight
            if (Math.abs(currentRatio - targetAspectRatio) < 0.01f) {
                isAspectRatioCorrect = true
            }
        }

        if (isAspectRatioCorrect && isInsideBounds) {
            return
        }

        if (visibleBounds.width() <= 0 || visibleBounds.height() <= 0) return

        val centerX = cropRect.centerX().coerceIn(visibleBounds.left, visibleBounds.right)
        val centerY = cropRect.centerY().coerceIn(visibleBounds.top, visibleBounds.bottom)

        val maxHalfWidth = Math.min(centerX - visibleBounds.left, visibleBounds.right - centerX)
        val maxHalfHeight = Math.min(centerY - visibleBounds.top, visibleBounds.bottom - centerY)
        var newWidth = maxHalfWidth * 2
        var newHeight = maxHalfHeight * 2

        if (newWidth / newHeight > targetAspectRatio) {
            newWidth = newHeight * targetAspectRatio
        } else {
            newHeight = newWidth / targetAspectRatio
        }

        cropRect.set(
            centerX - newWidth / 2,
            centerY - newHeight / 2,
            centerX + newWidth / 2,
            centerY + newHeight / 2
        )
    }

    private fun getResizeHandle(x: Float, y: Float): Int {
        val cornerSize = 60f
        val left = cropRect.left
        val top = cropRect.top
        val right = cropRect.right
        val bottom = cropRect.bottom

        if (Math.abs(x - left) <= cornerSize && Math.abs(y - top) <= cornerSize) return 1
        if (Math.abs(x - right) <= cornerSize && Math.abs(y - top) <= cornerSize) return 2
        if (Math.abs(x - left) <= cornerSize && Math.abs(y - bottom) <= cornerSize) return 3
        if (Math.abs(x - right) <= cornerSize && Math.abs(y - bottom) <= cornerSize) return 4
        return 0
    }

    private fun resizeCropRect(x: Float, y: Float) {
        val minSize = 50f
        val currentRight = cropRect.right
        val currentBottom = cropRect.bottom
        val currentLeft = cropRect.left
        val currentTop = cropRect.top

        var newLeft = currentLeft
        var newTop = currentTop
        var newRight = currentRight
        var newBottom = currentBottom

        when (resizeHandle) {
            1 -> { newLeft = x.coerceAtMost(currentRight - minSize); newTop = y.coerceAtMost(currentBottom - minSize) }
            2 -> { newRight = x.coerceAtLeast(currentLeft + minSize); newTop = y.coerceAtMost(currentBottom - minSize) }
            3 -> { newLeft = x.coerceAtMost(currentRight - minSize); newBottom = y.coerceAtLeast(currentTop + minSize) }
            4 -> { newRight = x.coerceAtLeast(currentLeft + minSize); newBottom = y.coerceAtLeast(currentTop + minSize) }
        }

        var aspectRatio = 0f
        when (currentCropMode) {
            CropMode.SQUARE -> aspectRatio = 1f
            CropMode.PORTRAIT -> aspectRatio = 9f / 16f
            CropMode.LANDSCAPE -> aspectRatio = 16f / 9f
            else -> {}
        }

        if (aspectRatio > 0) {
            val (fixedX, fixedY) = when (resizeHandle) {
                1 -> Pair(currentRight, currentBottom)
                2 -> Pair(currentLeft, currentBottom)
                3 -> Pair(currentRight, currentTop)
                4 -> Pair(currentLeft, currentTop)
                else -> Pair(currentLeft, currentTop)
            }

            var desiredWidth = Math.abs(x - fixedX)
            var desiredHeight = Math.abs(y - fixedY)

            val (maxAllowedWidth, maxAllowedHeight) = when (resizeHandle) {
                1 -> Pair(Math.min(fixedX - imageBounds.left, fixedX - 0f), Math.min(fixedY - imageBounds.top, fixedY - 0f))
                2 -> Pair(Math.min(imageBounds.right - fixedX, width.toFloat() - fixedX), Math.min(fixedY - imageBounds.top, fixedY - 0f))
                3 -> Pair(Math.min(fixedX - imageBounds.left, fixedX - 0f), Math.min(imageBounds.bottom - fixedY, height.toFloat() - fixedY))
                4 -> Pair(Math.min(imageBounds.right - fixedX, width.toFloat() - fixedX), Math.min(imageBounds.bottom - fixedY, height.toFloat() - fixedY))
                else -> Pair(Math.min(imageBounds.width(), width.toFloat()), Math.min(imageBounds.height(), height.toFloat()))
            }

            if (desiredWidth / desiredHeight > aspectRatio) {
                desiredWidth = Math.min(desiredWidth, maxAllowedWidth)
                desiredHeight = desiredWidth / aspectRatio
                if (desiredHeight > maxAllowedHeight) {
                    desiredHeight = maxAllowedHeight
                    desiredWidth = desiredHeight * aspectRatio
                }
            } else {
                desiredHeight = Math.min(desiredHeight, maxAllowedHeight)
                desiredWidth = desiredHeight * aspectRatio
                if (desiredWidth > maxAllowedWidth) {
                    desiredWidth = maxAllowedWidth
                    desiredHeight = desiredWidth / aspectRatio
                }
            }

            if (desiredWidth < minSize) { desiredWidth = minSize; desiredHeight = minSize / aspectRatio }
            if (desiredHeight < minSize) { desiredHeight = minSize; desiredWidth = minSize * aspectRatio }

            when (resizeHandle) {
                1 -> { newLeft = fixedX - desiredWidth; newTop = fixedY - desiredHeight }
                2 -> { newRight = fixedX + desiredWidth; newTop = fixedY - desiredHeight }
                3 -> { newLeft = fixedX - desiredWidth; newBottom = fixedY + desiredHeight }
                4 -> { newRight = fixedX + desiredWidth; newBottom = fixedY + desiredHeight }
            }
        }
        cropRect.set(newLeft, newTop, newRight, newBottom)
    }

    private fun updateCropRect(x: Float, y: Float) {
        when (currentCropMode) {
            CropMode.FREEFORM -> { cropRect.right = x; cropRect.bottom = y }
            CropMode.SQUARE -> { val size = Math.max(x - cropRect.left, y - cropRect.top); cropRect.right = cropRect.left + size; cropRect.bottom = cropRect.top + size }
            CropMode.PORTRAIT -> { val width = x - cropRect.left; cropRect.right = x; cropRect.bottom = cropRect.top + width * 16 / 9 }
            CropMode.LANDSCAPE -> { val height = y - cropRect.top; cropRect.right = cropRect.left + height * 16 / 9; cropRect.bottom = y }
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
        val translation = brightness + (1f - contrast) * 128f
        
        colorMatrix.set(floatArrayOf(
            contrast, 0f, 0f, 0f, translation,
            0f, contrast, 0f, 0f, translation,
            0f, 0f, contrast, 0f, translation,
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