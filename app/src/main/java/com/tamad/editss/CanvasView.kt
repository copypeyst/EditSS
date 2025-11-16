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

    // Gesture detection - REMOVED scale detector for crop mode
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

    // Handle crop redo - reapply the crop


    fun handleCropRedo(cropAction: CropAction) {
        // This function should ideally restore the bitmap to the state *after* the crop.
        // However, CropAction only contains previousBitmap.
        // The actual redo of a crop is handled by EditAction.BitmapChange.
        // So, if this is called, it means an EditAction.Crop was in the redo stack,
        // which might indicate an inconsistency in the action management.
        // For now, we do nothing here, as the BitmapChange action should handle the actual bitmap update.
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

    // Removed processUndoAction and processRedoAction - now using direct handlers for simplicity

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
                        // Fill the visible area completely
                        width = visibleBounds.width()
                        height = visibleBounds.height()
                    }
                    CropMode.SQUARE -> {
                        // 1:1 ratio - fit the smaller dimension of visible area
                        val size = Math.min(visibleBounds.width(), visibleBounds.height())
                        width = size
                        height = size
                    }
                    CropMode.PORTRAIT -> {
                        // 9:16 ratio (width:height) - taller rectangle
                        // Always calculate based on height to maintain proper aspect ratio
                        height = visibleBounds.height()
                        width = height * 9 / 16f
                        
                        // Ensure width doesn't exceed visible width
                        if (width > visibleBounds.width()) {
                            width = visibleBounds.width()
                            height = width * 16 / 9f
                        }
                        
                        // Ensure minimum reasonable size
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
                        // 16:9 ratio (width:height) - wider rectangle
                        // Always calculate based on width to maintain proper aspect ratio
                        width = visibleBounds.width()
                        height = width * 9 / 16f
                        
                        // Ensure height doesn't exceed visible height
                        if (height > visibleBounds.height()) {
                            height = visibleBounds.height()
                            width = height * 16 / 9f
                        }
                        
                        // Ensure minimum reasonable size
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

                // Ensure it's within bounds (should already be, but safety check)
                clampCropRectToBounds()
            }
        }
    }

    private fun getVisibleImageBounds(): RectF {
        // Calculate the actual visible bounds of the image in screen coordinates
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

    private fun clampCropRectToScreen() {
        // Clamp to screen boundaries (0, 0, width, height)
        cropRect.left = cropRect.left.coerceIn(0f, width.toFloat())
        cropRect.top = cropRect.top.coerceIn(0f, height.toFloat())
        cropRect.right = cropRect.right.coerceIn(0f, width.toFloat())
        cropRect.bottom = cropRect.bottom.coerceIn(0f, height.toFloat())
    }

    private fun clampCropRectToBounds() {
        // Use the more sophisticated visible bounds constraint
        clampCropRectToVisibleImage()
    }
    
    fun cancelCrop() {
        cropRect.setEmpty()
        invalidate()
        onCropCanceled?.invoke() // Invoke callback
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

        // If in sketch mode, we must transform the coordinates of every stored path
        // to match the new, smaller canvas. This keeps the "recipe" of strokes in sync.
        if (isSketchMode) {
            val translationMatrix = Matrix()
            // Create a matrix that will shift every path up and to the left by the crop amount.
            translationMatrix.postTranslate(-left, -top)

            val updatedStrokes = sketchStrokes.map { stroke ->
                val newPath = Path()
                stroke.path.transform(translationMatrix, newPath)
                DrawingAction(newPath, stroke.paint) // Stroke width is already in bitmap space
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
        // Since drawings are immediately merged into bitmap, just return baseBitmap
        return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun getDrawingOnTransparent(): Bitmap? {
        // Since drawings are merged into bitmap, return a copy of baseBitmap
        return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }
    
    fun getTransparentDrawing(): Bitmap? {
        if (isSketchMode) {
            // Create transparent bitmap and render only strokes
            baseBitmap?.let { originalBitmap ->
                val transparentBitmap = Bitmap.createBitmap(
                    originalBitmap.width,
                    originalBitmap.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(transparentBitmap)
                val tempPaint = Paint()
                
                // Render all sketch strokes. They are already in the correct bitmap coordinate space.
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
        // The baseBitmap is always the most up-to-date representation for both sketch and non-sketch modes.
        return baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun getFinalBitmap(): Bitmap? {
        // Since drawings are immediately merged into bitmap, just return baseBitmap
        return baseBitmap
    }
    
    fun convertTransparentToWhite(bitmap: Bitmap): Bitmap {
        // Create white bitmap of same size
        val whiteBitmap = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(whiteBitmap)
        val paint = Paint()
        
        // Fill with white background
        canvas.drawColor(Color.WHITE)
        
        // Draw the original bitmap on top
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

    fun mergeDrawingActions() {
        // This method is now deprecated since drawings are immediately merged
        // Kept for compatibility but does nothing
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

        // Draw crop overlay if in crop mode
        if (currentTool == ToolType.CROP && !cropRect.isEmpty) {
            drawCropOverlay(canvas)
        }

        baseBitmap?.let {
            canvas.restore()
        }
    }

    private fun drawCropOverlay(canvas: Canvas) {
        // Create a semi-transparent dark overlay paint with anti-aliasing disabled for cleaner edges
        val overlayPaint = Paint().apply {
            color = Color.BLACK
            alpha = 128 // 50% opacity for dark overlay
            isAntiAlias = false // Disable anti-aliasing to prevent thin line artifacts
        }

        // Create a path for the overlay to avoid gaps between rectangles
        val overlayPath = Path()
        
        // Draw the entire screen area
        overlayPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        
        // Subtract the crop rectangle from the overlay
        overlayPath.addRect(cropRect, Path.Direction.CCW)
        
        // Fill the overlay path (this creates a hole where the crop rectangle is)
        canvas.drawPath(overlayPath, overlayPaint)

        // Draw the crop rectangle border on top
        canvas.drawRect(cropRect, cropPaint)

        // Draw corner indicators
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDrawing = false
            }
            
            val screenSpaceAction = currentDrawingTool.onTouchEvent(event, paint)
            screenSpaceAction?.let { action ->
                val bitmapBeforeDrawing = baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                // Always draw the screen-space stroke onto the visible bitmap first
                mergeDrawingStrokeIntoBitmap(action)

                var finalAssociatedStroke: DrawingAction? = null
                if (isSketchMode) {
                    // For the permanent record, convert the stroke to bitmap space
                    val inverseMatrix = Matrix()
                    imageMatrix.invert(inverseMatrix)
                    
                    // Create a new Paint object for storage with the correctly scaled stroke width
                    val bitmapSpacePaint = Paint(paint) // Copy all paint properties
                    // mapRadius correctly scales the stroke width based on the current zoom/crop
                    val bitmapStrokeWidth = inverseMatrix.mapRadius(paint.strokeWidth)
                    bitmapSpacePaint.strokeWidth = bitmapStrokeWidth
                    
                    // Transform the path to bitmap coordinates
                    val bitmapPath = Path()
                    action.path.transform(inverseMatrix, bitmapPath)
                    
                    // Create and store the new action which is now fully in bitmap space
                    val bitmapSpaceAction = DrawingAction(bitmapPath, bitmapSpacePaint)
                    sketchStrokes.add(bitmapSpaceAction)
                    undoneSketchStrokes.clear()
                    finalAssociatedStroke = bitmapSpaceAction
                }

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
                    if (cropRect.width() > 0 && cropRect.height() > 0) {
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
                    if (!cropRect.isEmpty) {
                        return true
                    }
                    if (!isCropModeActive) {
                        return true
                    }
                    isCropping = true
                    cropRect.left = x; cropRect.top = y
                    cropRect.right = x; cropRect.bottom = y
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
                        cropRect.left = cropStartLeft + dx; cropRect.top = cropStartTop + dy
                        cropRect.right = cropStartRight + dx; cropRect.bottom = cropStartBottom + dy
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
        
        // This logic remains from your baseline, it can be refined but is functional.
        val currentWidth = cropRect.width()
        val currentHeight = currentWidth / targetAspectRatio
        cropRect.bottom = cropRect.top + currentHeight
        clampCropRectToBounds()
        invalidate()
    }

    private fun getResizeHandle(x: Float, y: Float): Int {
        val cornerSize = 60f
        if (Math.abs(x - cropRect.left) <= cornerSize && Math.abs(y - cropRect.top) <= cornerSize) return 1
        if (Math.abs(x - cropRect.right) <= cornerSize && Math.abs(y - cropRect.top) <= cornerSize) return 2
        if (Math.abs(x - cropRect.left) <= cornerSize && Math.abs(y - cropRect.bottom) <= cornerSize) return 3
        if (Math.abs(x - cropRect.right) <= cornerSize && Math.abs(y - cropRect.bottom) <= cornerSize) return 4
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
        cropRect.right = x
        cropRect.bottom = y
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