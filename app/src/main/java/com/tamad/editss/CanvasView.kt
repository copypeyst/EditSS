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

    fun applyCrop(): Bitmap? {
        if (baseBitmap == null || cropRect.isEmpty) return null

        // Since drawings are immediately merged into bitmap, we can use baseBitmap directly
        val bitmapWithDrawings = baseBitmap ?: return null

        // Store the state before the crop for the undo action.
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
        
        // Create ONLY a BitmapChange action for clean undo/redo - no separate CropAction
        val bitmapChangeAction = EditAction.BitmapChange(
            previousBitmap = previousBaseBitmap,
            newBitmap = baseBitmap!!
        )
        onBitmapChanged?.invoke(bitmapChangeAction) // Push the BitmapChange to the ViewModel

        // Clear the crop rectangle and reset zoom to default view (recenter)
        cropRect.setEmpty()
        scaleFactor = 1.0f
        translationX = 0f
        translationY = 0f
        updateImageMatrix()
        invalidate()
        onCropApplied?.invoke(baseBitmap!!)

        return baseBitmap
    }

    fun cancelCrop() {
        cropRect.setEmpty()
        invalidate()
        onCropCanceled?.invoke() // Invoke callback
    }

    private fun updateImageBounds() {
        baseBitmap?.let {
            val bitmapWidth = it.width.toFloat()
            val bitmapHeight = it.height.toFloat()
            imageBounds.set(0f, 0f, bitmapWidth, bitmapHeight)
            imageMatrix.mapRect(imageBounds)
        }
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
                val paint = Paint()
                
                // Render all sketch strokes
                for (stroke in sketchStrokes) {
                    paint.color = stroke.paint.color
                    paint.strokeWidth = stroke.paint.strokeWidth
                    paint.alpha = stroke.paint.alpha
                    paint.style = stroke.paint.style
                    paint.strokeJoin = stroke.paint.strokeJoin
                    paint.strokeCap = stroke.paint.strokeCap
                    paint.isAntiAlias = stroke.paint.isAntiAlias
                    
                    canvas.drawPath(stroke.path, paint)
                }
                
                return transparentBitmap
            }
        }
        // Non-sketch mode: use original method
        return getDrawingOnTransparent()
    }
    
    fun getSketchDrawingOnWhite(): Bitmap? {
        if (isSketchMode) {
            // Create white bitmap and render strokes on top
            baseBitmap?.let { originalBitmap ->
                val whiteBitmap = Bitmap.createBitmap(
                    originalBitmap.width,
                    originalBitmap.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(whiteBitmap)
                val paint = Paint()
                
                // Fill with white background
                canvas.drawColor(Color.WHITE)
                
                // Render all sketch strokes
                for (stroke in sketchStrokes) {
                    paint.color = stroke.paint.color
                    paint.strokeWidth = stroke.paint.strokeWidth
                    paint.alpha = stroke.paint.alpha
                    paint.style = stroke.paint.style
                    paint.strokeJoin = stroke.paint.strokeJoin
                    paint.strokeCap = stroke.paint.strokeCap
                    paint.isAntiAlias = stroke.paint.isAntiAlias
                    
                    canvas.drawPath(stroke.path, paint)
                }
                
                return whiteBitmap
            }
        }
        // Non-sketch mode: use original method
        return getDrawing()
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
        
        // Paths are cleared immediately after merging into bitmap (no need to track separately)
        
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
        // Create a semi-transparent dark overlay paint
        val overlayPaint = Paint()
        overlayPaint.color = Color.BLACK
        overlayPaint.alpha = 128 // 50% opacity for dark overlay

        // Draw dark overlay outside the crop rectangle
        // Top area
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, overlayPaint)
        // Left area
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, overlayPaint)
        // Right area
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, overlayPaint)
        // Bottom area
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), overlayPaint)

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
        // Track pointer count for scale detection
        lastPointerCount = event.pointerCount
        
        scaleGestureDetector.onTouchEvent(event)

        val x = event.x
        val y = event.y

        // Handle multi-touch: cancel drawing if second touch is detected
        if (event.pointerCount > 1 && isDrawing && currentTool == ToolType.DRAW) {
            // Cancel the current drawing
            currentDrawingTool.onTouchEvent(MotionEvent.obtain(event.downTime, event.eventTime, MotionEvent.ACTION_CANCEL, event.x, event.y, 0), paint)
            isDrawing = false
            invalidate()
            return true
        }

        // Handle multi-touch: cancel crop rectangle manipulation if second touch is detected
        if (event.pointerCount > 1 && currentTool == ToolType.CROP && (isMovingCropRect || isResizingCropRect)) {
            // Cancel the current crop rectangle manipulation
            isMovingCropRect = false
            isResizingCropRect = false
            resizeHandle = 0
            invalidate()
            return true
        }

        if (isZooming || event.pointerCount > 1) {
            // Only allow panning when zoomed in (scaleFactor > 1.0f)
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
            
            val action = currentDrawingTool.onTouchEvent(event, paint)
            action?.let {
                val bitmapBeforeDrawing = baseBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                if (isSketchMode) {
                    sketchStrokes.add(action)
                    undoneSketchStrokes.clear() // Clear redo stack on new action
                }
                mergeDrawingStrokeIntoBitmap(action)

                if (bitmapBeforeDrawing != null) {
                    onBitmapChanged?.invoke(EditAction.BitmapChange(
                        previousBitmap = bitmapBeforeDrawing,
                        newBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true),
                        associatedStroke = if (isSketchMode) action else null
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
                    // Check if touch is on a corner (for resizing) - only if rectangle exists
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
                    // Check if touch is inside the crop rectangle (for moving)
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
                    // If a cropRect already exists and we are not resizing or moving it, do nothing.
                    // This prevents creating a new cropRect when touching outside an existing one.
                    if (!cropRect.isEmpty) {
                        return true // Consume the event but do nothing
                    }
                    // Only allow crop creation if a crop mode is actually active/selected
                    if (!isCropModeActive) {
                        return true // Consume the event but do nothing if no crop mode selected
                    }
                    // Create new crop rectangle
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
    
                        // Calculate potential new cropRect position
                        val newLeft = cropStartLeft + dx
                        val newTop = cropStartTop + dy
                        val newRight = cropStartRight + dx
                        val newBottom = cropStartBottom + dy
    
                        // Get current visible bounds (intersection of image and screen)
                        val visibleBounds = getVisibleImageBounds()
    
                        // Adjust dx and dy to prevent moving outside visible image area
                        if (newLeft < visibleBounds.left) {
                            dx = visibleBounds.left - cropStartLeft
                        }
                        if (newTop < visibleBounds.top) {
                            dy = visibleBounds.top - cropStartTop
                        }
                        if (newRight > visibleBounds.right) {
                            dx = visibleBounds.right - cropStartRight
                        }
                        if (newBottom > visibleBounds.bottom) {
                            dy = visibleBounds.bottom - cropStartBottom
                        }
    
                        // Apply the adjusted dx and dy
                        cropRect.left = cropStartLeft + dx
                        cropRect.top = cropStartTop + dy
                        cropRect.right = cropStartRight + dx
                        cropRect.bottom = cropStartBottom + dy
    
                        // Final safety clamp to both image and screen boundaries
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

        // Check if the current aspect ratio is already correct (within a small tolerance)
        val currentWidth = cropRect.width()
        val currentHeight = cropRect.height()
        var isAspectRatioCorrect = false
        if (currentWidth > 0 && currentHeight > 0) {
            val currentRatio = currentWidth / currentHeight
            if (Math.abs(currentRatio - targetAspectRatio) < 0.01f) {
                isAspectRatioCorrect = true
            }
        }

        // If the aspect ratio is correct AND the rect is fully inside the visible bounds, do nothing.
        if (isAspectRatioCorrect && isInsideBounds) {
            return
        }

        // If we are here, the rect is either out of bounds or has the wrong aspect ratio.
        // Fix it by resizing to max possible while respecting the center.
        if (visibleBounds.width() <= 0 || visibleBounds.height() <= 0) return

        // Coerce center to be within the visible bounds, so we don't try to fix a rect that's completely off-screen
        val centerX = cropRect.centerX().coerceIn(visibleBounds.left, visibleBounds.right)
        val centerY = cropRect.centerY().coerceIn(visibleBounds.top, visibleBounds.bottom)

        // Determine max possible width and height from the (potentially coerced) center to the edges of visibleBounds
        val maxHalfWidth = Math.min(centerX - visibleBounds.left, visibleBounds.right - centerX)
        val maxHalfHeight = Math.min(centerY - visibleBounds.top, visibleBounds.bottom - centerY)
        var newWidth = maxHalfWidth * 2
        var newHeight = maxHalfHeight * 2

        // Now, fit the aspect ratio within this max bounding box
        if (newWidth / newHeight > targetAspectRatio) {
            // The available box is wider than the target aspect ratio, so height is the limiting dimension
            newWidth = newHeight * targetAspectRatio
        } else {
            // The available box is taller than or equal to the target aspect ratio, so width is the limiting dimension
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
        val edgeSize = 30f
        val left = cropRect.left
        val top = cropRect.top
        val right = cropRect.right
        val bottom = cropRect.bottom

        // Check top-left corner
        if (Math.abs(x - left) <= cornerSize && Math.abs(y - top) <= cornerSize) {
            return 1
        }
        // Check top-right corner
        if (Math.abs(x - right) <= cornerSize && Math.abs(y - top) <= cornerSize) {
            return 2
        }
        // Check bottom-left corner
        if (Math.abs(x - left) <= cornerSize && Math.abs(y - bottom) <= cornerSize) {
            return 3
        }
        // Check bottom-right corner
        if (Math.abs(x - right) <= cornerSize && Math.abs(y - bottom) <= cornerSize) {
            return 4
        }
        return 0
    }

    private fun resizeCropRect(x: Float, y: Float) {
        val minSize = 50f
        var currentLeft = cropRect.left
        var currentTop = cropRect.top
        var currentRight = cropRect.right
        var currentBottom = cropRect.bottom

        var newLeft = currentLeft
        var newTop = currentTop
        var newRight = currentRight
        var newBottom = currentBottom

        // First, update the corner being dragged
        when (resizeHandle) {
            1 -> { // top-left
                newLeft = x.coerceAtMost(currentRight - minSize)
                newTop = y.coerceAtMost(currentBottom - minSize)
            }
            2 -> { // top-right
                newRight = x.coerceAtLeast(currentLeft + minSize)
                newTop = y.coerceAtMost(currentBottom - minSize)
            }
            3 -> { // bottom-left
                newLeft = x.coerceAtMost(currentRight - minSize)
                newBottom = y.coerceAtLeast(currentTop + minSize)
            }
            4 -> { // bottom-right
                newRight = x.coerceAtLeast(currentLeft + minSize)
                newBottom = y.coerceAtLeast(currentTop + minSize)
            }
        }

        // Now, apply aspect ratio and clamp to image bounds
        var aspectRatio = 0f
        when (currentCropMode) {
            CropMode.SQUARE -> aspectRatio = 1f
            CropMode.PORTRAIT -> aspectRatio = 9f / 16f
            CropMode.LANDSCAPE -> aspectRatio = 16f / 9f
            CropMode.FREEFORM -> { /* No aspect ratio */ }
        }

        if (aspectRatio > 0) {
            // Calculate the fixed point (the corner opposite to the one being dragged)
            val fixedX: Float
            val fixedY: Float
            when (resizeHandle) {
                1 -> { fixedX = currentRight; fixedY = currentBottom } // top-left dragged, fixed bottom-right
                2 -> { fixedX = currentLeft; fixedY = currentBottom }  // top-right dragged, fixed bottom-left
                3 -> { fixedX = currentRight; fixedY = currentTop }   // bottom-left dragged, fixed top-right
                4 -> { fixedX = currentLeft; fixedY = currentTop }    // bottom-right dragged, fixed top-left
                else -> { fixedX = currentLeft; fixedY = currentTop } // Should not happen
            }

            // Calculate the desired width and height based on the current touch point relative to the fixed point
            var desiredWidth = Math.abs(x - fixedX)
            var desiredHeight = Math.abs(y - fixedY)

            // Determine the maximum allowed width and height based on both image bounds and screen bounds
            val maxAllowedWidth: Float
            val maxAllowedHeight: Float

            when (resizeHandle) {
                1 -> { // top-left
                    maxAllowedWidth = Math.min(fixedX - imageBounds.left, fixedX - 0f)
                    maxAllowedHeight = Math.min(fixedY - imageBounds.top, fixedY - 0f)
                }
                2 -> { // top-right
                    maxAllowedWidth = Math.min(imageBounds.right - fixedX, width.toFloat() - fixedX)
                    maxAllowedHeight = Math.min(fixedY - imageBounds.top, fixedY - 0f)
                }
                3 -> { // bottom-left
                    maxAllowedWidth = Math.min(fixedX - imageBounds.left, fixedX - 0f)
                    maxAllowedHeight = Math.min(imageBounds.bottom - fixedY, height.toFloat() - fixedY)
                }
                4 -> { // bottom-right
                    maxAllowedWidth = Math.min(imageBounds.right - fixedX, width.toFloat() - fixedX)
                    maxAllowedHeight = Math.min(imageBounds.bottom - fixedY, height.toFloat() - fixedY)
                }
                else -> {
                    maxAllowedWidth = Math.min(imageBounds.width(), width.toFloat())
                    maxAllowedHeight = Math.min(imageBounds.height(), height.toFloat())
                }
            }

            // Adjust desiredWidth/desiredHeight to maintain aspect ratio and fit within bounds
            if (desiredWidth / desiredHeight > aspectRatio) { // Too wide
                desiredWidth = Math.min(desiredWidth, maxAllowedWidth)
                desiredHeight = desiredWidth / aspectRatio
                if (desiredHeight > maxAllowedHeight) { // If height now exceeds, re-adjust
                    desiredHeight = maxAllowedHeight
                    desiredWidth = desiredHeight * aspectRatio
                }
            } else { // Too tall
                desiredHeight = Math.min(desiredHeight, maxAllowedHeight)
                desiredWidth = desiredHeight * aspectRatio
                if (desiredWidth > maxAllowedWidth) { // If width now exceeds, re-adjust
                    desiredWidth = maxAllowedWidth
                    desiredHeight = desiredWidth / aspectRatio
                }
            }

            // Ensure minimum size
            if (desiredWidth < minSize) {
                desiredWidth = minSize
                desiredHeight = minSize / aspectRatio
            }
            if (desiredHeight < minSize) {
                desiredHeight = minSize
                desiredWidth = minSize * aspectRatio
            }

            // Reconstruct newLeft, newTop, newRight, newBottom based on fixed point and new desiredWidth/desiredHeight
            when (resizeHandle) {
                1 -> { // top-left
                    newLeft = fixedX - desiredWidth
                    newTop = fixedY - desiredHeight
                }
                2 -> { // top-right
                    newRight = fixedX + desiredWidth
                    newTop = fixedY - desiredHeight
                }
                3 -> { // bottom-left
                    newLeft = fixedX - desiredWidth
                    newBottom = fixedY + desiredHeight
                }
                4 -> { // bottom-right
                    newRight = fixedX + desiredWidth
                    newBottom = fixedY + desiredHeight
                }
            }
        }

        // Set the new values (aspect ratio and boundary constraints already applied above)
        cropRect.set(newLeft, newTop, newRight, newBottom)
    }

    private fun updateCropRect(x: Float, y: Float) {
        when (currentCropMode) {
            CropMode.FREEFORM -> {
                cropRect.right = x
                cropRect.bottom = y
            }
            CropMode.SQUARE -> {
                val width = x - cropRect.left
                val height = y - cropRect.top
                val size = Math.max(width, height)
                cropRect.right = cropRect.left + size
                cropRect.bottom = cropRect.top + size
            }
            CropMode.PORTRAIT -> {
                val width = x - cropRect.left
                val height = width * 16 / 9
                cropRect.right = x
                cropRect.bottom = cropRect.top + height
            }
            CropMode.LANDSCAPE -> {
                val height = y - cropRect.top
                val width = height * 16 / 9
                cropRect.right = cropRect.left + width
                cropRect.bottom = y
            }
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
        
        // Proper contrast and brightness calculation
        // Formula: new_value = ((old_value - 128) * contrast) + 128 + brightness
        // This ensures:
        // - When contrast = 0: all values become 128 + brightness (neutral gray)
        // - When contrast = 1: values remain at old_value + brightness (original + brightness)
        // - When contrast > 1: increases contrast around 128
        // - When contrast < 1: decreases contrast around 128
        
        val translation = brightness + (1f - contrast) * 128f
        
        colorMatrix.set(floatArrayOf(
            contrast, 0f, 0f, 0f, translation,
            0f, contrast, 0f, 0f, translation,
            0f, 0f, contrast, 0f, translation,
            0f, 0f, 0f, 1f, 0f
        ))

        // Saturation
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