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
import com.tamad.editss.DrawingAction
import com.tamad.editss.CropMode
import com.tamad.editss.CropAction
import com.tamad.editss.EditAction

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
    private var paths = listOf<DrawingAction>()
    private val imageMatrix = android.graphics.Matrix()
    private val imageBounds = RectF()


    private var currentTool: ToolType = ToolType.DRAW
    private var currentCropMode: CropMode = CropMode.FREEFORM

    private var isCropping = false
    private var cropRect = RectF()
    private var isMovingCropRect = false
    private var isResizingCropRect = false
    private var resizeHandle: Int = 0 // 0=none, 1=top-left, 2=top-right, 3=bottom-left, 4=bottom-right

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

    var onNewPath: ((DrawingAction) -> Unit)? = null
    var onCropApplied: ((Bitmap) -> Unit)? = null
    var onCropCanceled: (() -> Unit)? = null
    var onCropAction: ((CropAction) -> Unit)? = null // New callback for crop actions
    var onUndoAction: ((EditAction) -> Unit)? = null // Callback for undo operations
    var onRedoAction: ((EditAction) -> Unit)? = null // Callback for redo operations

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
        post {
            if (currentTool == ToolType.CROP) {
                initializeDefaultCropRect()
            }
        }
    }

    fun setPaths(paths: List<DrawingAction>) {
        this.paths = paths
        invalidate()
    }

    fun getBaseBitmap(): Bitmap? = baseBitmap

    // Handle undo/redo operations for the unified action system
    fun handleUndo(actions: List<EditAction>) {
        // Extract only drawing actions from the unified action list
        val drawingActions = actions.filterIsInstance<EditAction.Drawing>().map { it.action }
        this.paths = drawingActions
        invalidate()
    }

    fun handleRedo(actions: List<EditAction>) {
        // Extract only drawing actions from the unified action list
        val drawingActions = actions.filterIsInstance<EditAction.Drawing>().map { it.action }
        this.paths = drawingActions
        invalidate()
    }

    // Handle crop undo - restore previous bitmap state
    fun handleCropUndo(cropAction: CropAction) {
        // Restore the bitmap to the state before the crop
        baseBitmap = cropAction.previousBitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        // Update the image matrix to properly display the restored bitmap
        updateImageMatrix()
        
        // Clear any existing crop rectangle
        cropRect.setEmpty()
        
        // Redraw the canvas
        invalidate()
    }

    // Handle crop redo - reapply the crop
    fun handleCropRedo(cropAction: CropAction) {
        // For redo, we need to reapply the crop to the current bitmap
        // The cropAction.cropRect contains the rectangle that was applied
        // and the current baseBitmap should be the one before the crop
        
        if (baseBitmap != null && !cropAction.cropRect.isEmpty) {
            // Map crop rectangle from screen coordinates to image coordinates
            val inverseMatrix = Matrix()
            imageMatrix.invert(inverseMatrix)

            val imageCropRect = RectF()
            inverseMatrix.mapRect(imageCropRect, cropAction.cropRect)

            // Clamp to image bounds
            val left = imageCropRect.left.coerceIn(0f, baseBitmap!!.width.toFloat())
            val top = imageCropRect.top.coerceIn(0f, baseBitmap!!.height.toFloat())
            val right = imageCropRect.right.coerceIn(0f, baseBitmap!!.width.toFloat())
            val bottom = imageCropRect.bottom.coerceIn(0f, baseBitmap!!.height.toFloat())

            if (right > left && bottom > top) {
                val croppedBitmap = Bitmap.createBitmap(
                    baseBitmap!!,
                    left.toInt(),
                    top.toInt(),
                    (right - left).toInt(),
                    (bottom - top).toInt()
                )

                baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                updateImageMatrix()
                cropRect.setEmpty()
                invalidate()
            }
        }
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

    // Process a single action for undo
    fun processUndoAction(action: EditAction) {
        when (action) {
            is EditAction.Drawing -> {
                // Drawing actions are handled by the setPaths method
                // No additional handling needed here
            }
            is EditAction.Crop -> {
                handleCropUndo(action.action)
            }
            is EditAction.Adjust -> {
                handleAdjustUndo(action.action)
            }
        }
    }

    // Process a single action for redo
    fun processRedoAction(action: EditAction) {
        when (action) {
            is EditAction.Drawing -> {
                // Drawing actions are handled by the setPaths method
                // No additional handling needed here
            }
            is EditAction.Crop -> {
                handleCropRedo(action.action)
            }
            is EditAction.Adjust -> {
                handleAdjustRedo(action.action)
            }
        }
    }

    fun setToolType(toolType: ToolType) {
        if (currentTool == ToolType.DRAW && toolType != ToolType.DRAW) {
            mergeDrawingActions()
        }
        this.currentTool = toolType
        if (toolType == ToolType.CROP) {
            initializeDefaultCropRect()
        }
        invalidate()
    }

    fun setCropMode(cropMode: CropMode) {
        this.currentCropMode = cropMode
        if (currentTool == ToolType.CROP) {
            initializeDefaultCropRect()
        }
        invalidate()
    }

    private fun resetCropRect() {
        cropRect.setEmpty()
    }

    private fun initializeDefaultCropRect() {
        // Set default crop rectangle to fill 100% of the available image space
        if (imageBounds.width() > 0 && imageBounds.height() > 0) {
            // No padding - fill 100% of available image space
            val availableWidth = imageBounds.width()
            val availableHeight = imageBounds.height()

            // Calculate dimensions based on aspect ratio
            var width = 0f
            var height = 0f

            when (currentCropMode) {
                CropMode.FREEFORM -> {
                    // Fill 100% of available space
                    width = availableWidth
                    height = availableHeight
                }
                CropMode.SQUARE -> {
                    // 1:1 ratio - use the smaller dimension
                    val size = Math.min(availableWidth, availableHeight)
                    width = size
                    height = size
                }
                CropMode.PORTRAIT -> {
                    // 9:16 ratio (width:height) - taller image
                    val maxWidthByHeight = availableHeight * 9 / 16f
                    width = Math.min(availableWidth, maxWidthByHeight)
                    height = width * 16 / 9f
                }
                CropMode.LANDSCAPE -> {
                    // 16:9 ratio (width:height) - wider image
                    val maxHeightByWidth = availableWidth * 9 / 16f
                    height = Math.min(availableHeight, maxHeightByWidth)
                    width = height * 16 / 9f
                }
            }

            // Center the rectangle
            val centerX = (imageBounds.left + imageBounds.right) / 2
            val centerY = (imageBounds.top + imageBounds.bottom) / 2

            cropRect.left = centerX - width / 2
            cropRect.top = centerY - height / 2
            cropRect.right = centerX + width / 2
            cropRect.bottom = centerY + height / 2

            // Ensure it's within image bounds
            clampCropRectToImage()
        }
    }

    private fun clampCropRectToImage() {
        if (imageBounds.width() <= 0) return

        cropRect.left = cropRect.left.coerceIn(imageBounds.left, imageBounds.right)
        cropRect.top = cropRect.top.coerceIn(imageBounds.top, imageBounds.bottom)
        cropRect.right = cropRect.right.coerceIn(imageBounds.left, imageBounds.right)
        cropRect.bottom = cropRect.bottom.coerceIn(imageBounds.top, imageBounds.bottom)
    }

    fun applyCrop(): Bitmap? {
        if (baseBitmap == null || cropRect.isEmpty) return null

        // Store the previous bitmap state for undo/redo
        val previousBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true)

        // Map crop rectangle from screen coordinates to image coordinates
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)

        val imageCropRect = RectF()
        inverseMatrix.mapRect(imageCropRect, cropRect)

        // Clamp to image bounds
        val left = imageCropRect.left.coerceIn(0f, baseBitmap!!.width.toFloat())
        val top = imageCropRect.top.coerceIn(0f, baseBitmap!!.height.toFloat())
        val right = imageCropRect.right.coerceIn(0f, baseBitmap!!.width.toFloat())
        val bottom = imageCropRect.bottom.coerceIn(0f, baseBitmap!!.height.toFloat())

        if (right <= left || bottom <= top) return null

        val croppedBitmap = Bitmap.createBitmap(
            baseBitmap!!,
            left.toInt(),
            top.toInt(),
            (right - left).toInt(),
            (bottom - top).toInt()
        )

        baseBitmap = croppedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        // Create crop action for undo/redo
        val cropAction = CropAction(
            previousBitmap = previousBitmap,
            cropRect = android.graphics.RectF(cropRect),
            cropMode = currentCropMode
        )
        
        // Notify ViewModel about the crop action
        onCropAction?.invoke(cropAction)
        
        cropRect.setEmpty()

        // Update image matrix to center the new image (do NOT reset)
        updateImageMatrix()
        invalidate()
        onCropApplied?.invoke(baseBitmap!!) // Invoke callback

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
        if (baseBitmap == null) return null
        val resultBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        canvas.concat(inverseMatrix)

        for (action in paths) {
            canvas.drawPath(action.path, action.paint)
        }
        return resultBitmap
    }

    fun getDrawingOnTransparent(): Bitmap? {
        if (baseBitmap == null) return null
        val resultBitmap = Bitmap.createBitmap(baseBitmap!!.width, baseBitmap!!.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        canvas.concat(inverseMatrix)

        for (action in paths) {
            canvas.drawPath(action.path, action.paint)
        }
        return resultBitmap
    }

    fun getFinalBitmap(): Bitmap? {
        mergeDrawingActions()
        return baseBitmap
    }

    fun mergeDrawingActions() {
        if (baseBitmap == null) return
        val canvas = Canvas(baseBitmap!!)
        val inverseMatrix = Matrix()
        imageMatrix.invert(inverseMatrix)
        canvas.concat(inverseMatrix)

        for (action in paths) {
            canvas.drawPath(action.path, action.paint)
        }

        paths = emptyList()
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

        for (action in paths) {
            canvas.drawPath(action.path, action.paint)
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

            val scale: Float
            var dx = 0f
            var dy = 0f

            if (bitmapWidth / viewWidth > bitmapHeight / viewHeight) {
                scale = viewWidth / bitmapWidth
                dy = (viewHeight - bitmapHeight * scale) * 0.5f
            } else {
                scale = viewHeight / bitmapHeight
                dx = (viewWidth - bitmapWidth * scale) * 0.5f
            }

            imageMatrix.setScale(scale, scale)
            imageMatrix.postTranslate(dx, dy)

            imageBounds.set(0f, 0f, bitmapWidth, bitmapHeight)
            imageMatrix.mapRect(imageBounds)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        if (currentTool == ToolType.DRAW) {
            val action = currentDrawingTool.onTouchEvent(event, paint)
            action?.let {
                onNewPath?.invoke(it)
                paths = paths + it
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
                    // Otherwise create new crop rectangle
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
                        clampCropRectToImage()
                        invalidate()
                    } else if (isMovingCropRect) {
                        var dx = x - cropStartX
                        var dy = y - cropStartY

                        // Calculate potential new cropRect position
                        val newLeft = cropStartLeft + dx
                        val newTop = cropStartTop + dy
                        val newRight = cropStartRight + dx
                        val newBottom = cropStartBottom + dy

                        // Adjust dx and dy to prevent moving outside imageBounds
                        if (newLeft < imageBounds.left) {
                            dx = imageBounds.left - cropStartLeft
                        }
                        if (newTop < imageBounds.top) {
                            dy = imageBounds.top - cropStartTop
                        }
                        if (newRight > imageBounds.right) {
                            dx = imageBounds.right - cropStartRight
                        }
                        if (newBottom > imageBounds.bottom) {
                            dy = imageBounds.bottom - cropStartBottom
                        }

                        // Apply the adjusted dx and dy
                        cropRect.left = cropStartLeft + dx
                        cropRect.top = cropStartTop + dy
                        cropRect.right = cropStartRight + dx
                        cropRect.bottom = cropStartBottom + dy

                        // No need to call clampCropRectToImage() here as we've already handled clamping during movement
                        invalidate()
                    } else if (isCropping) {
                        updateCropRect(x, y)
                        clampCropRectToImage() // Add this line
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

            // Determine the maximum allowed width and height based on image bounds
            val maxAllowedWidth: Float
            val maxAllowedHeight: Float

            when (resizeHandle) {
                1 -> { // top-left
                    maxAllowedWidth = fixedX - imageBounds.left
                    maxAllowedHeight = fixedY - imageBounds.top
                }
                2 -> { // top-right
                    maxAllowedWidth = imageBounds.right - fixedX
                    maxAllowedHeight = fixedY - imageBounds.top
                }
                3 -> { // bottom-left
                    maxAllowedWidth = fixedX - imageBounds.left
                    maxAllowedHeight = imageBounds.bottom - fixedY
                }
                4 -> { // bottom-right
                    maxAllowedWidth = imageBounds.right - fixedX
                    maxAllowedHeight = imageBounds.bottom - fixedY
                }
                else -> { maxAllowedWidth = imageBounds.width(); maxAllowedHeight = imageBounds.height() }
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

        // Final clamp to ensure it's within image bounds (should be mostly handled above, but as a safeguard)
        newLeft = newLeft.coerceIn(imageBounds.left, imageBounds.right)
        newTop = newTop.coerceIn(imageBounds.top, imageBounds.bottom)
        newRight = newRight.coerceIn(imageBounds.left, imageBounds.right)
        newBottom = newBottom.coerceIn(imageBounds.top, imageBounds.bottom)

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
        // Brightness and Contrast
        colorMatrix.set(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
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