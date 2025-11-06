package com.tamad.editss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

enum class Tool {
    NONE,
    DRAW,
    CROP,
    ADJUST
}

class ImageCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val baseImageView: ImageView
    private var currentTool: Tool = Tool.NONE
    private var toolView: View? = null

    // The current image bitmap (mutable, so we can modify it)
    private var currentBitmap: Bitmap? = null

    // Store current paint settings so they can be applied to new tool views
    private var currentPaintColor: Int = android.graphics.Color.RED
    private var currentPaintSize: Float = 10f
    private var currentPaintOpacity: Int = 255
    private var currentDrawMode: DrawMode = DrawMode.PEN

    init {
        // Create the base ImageView that holds the image
        baseImageView = ImageView(context)
        addView(baseImageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Set the image to be displayed and edited
     */
    fun setImage(bitmap: Bitmap) {
        // Create a mutable copy of the bitmap so we can modify it
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        currentBitmap = mutableBitmap
        baseImageView.setImageBitmap(mutableBitmap)
    }

    /**
     * Set image bitmap (alias for setImage for compatibility)
     */
    fun setImageBitmap(bitmap: Bitmap?) {
        if (bitmap != null) {
            setImage(bitmap)
        } else {
            currentBitmap = null
            baseImageView.setImageBitmap(null)
        }
    }

    /**
     * Set background color
     */
    fun setBackgroundColor(color: Int) {
        baseImageView.setBackgroundColor(color)
    }

    /**
     * Get the base image view (for compatibility)
     */
    val baseImageView: ImageView get() = this.baseImageView

    /**
     * Get the current image bitmap (with modifications applied)
     */
    fun getBitmap(): Bitmap? {
        return currentBitmap
    }

    /**
     * Set which tool is currently active
     */
    fun setTool(tool: Tool) {
        if (currentTool == tool) return

        // Remove current tool view
        toolView?.let { removeView(it) }
        toolView = null

        currentTool = tool

        when (tool) {
            Tool.NONE -> {
                // No tool selected, just show the base image
            }
            Tool.DRAW -> {
                // Create and add DrawingView
                val drawingView = DrawingView(context, null)
                setupDrawingView(drawingView)
                addView(drawingView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                toolView = drawingView
            }
            Tool.CROP -> {
                // Create and add CropView
                val cropView = CropView(context, null)
                setupCropView(cropView)
                addView(cropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                toolView = cropView
            }
            Tool.ADJUST -> {
                // Create and add AdjustView
                val adjustView = AdjustView(context, null)
                setupAdjustView(adjustView)
                addView(adjustView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                toolView = adjustView
            }
        }
    }

    /**
     * Get the currently active tool
     */
    fun getCurrentTool(): Tool = currentTool

    /**
     * Set the paint color for the drawing tool
     */
    fun setPaintColor(color: Int) {
        currentPaintColor = color
        (toolView as? DrawingView)?.setPaintColor(color)
    }

    /**
     * Set the paint size for the drawing tool
     */
    fun setPaintSize(size: Float) {
        currentPaintSize = size
        (toolView as? DrawingView)?.setPaintSize(size)
    }

    /**
     * Set the paint opacity for the drawing tool
     */
    fun setPaintOpacity(opacity: Int) {
        currentPaintOpacity = opacity
        (toolView as? DrawingView)?.setPaintOpacity(opacity)
    }

    /**
     * Set the draw mode for the drawing tool
     */
    fun setDrawMode(drawMode: DrawMode) {
        currentDrawMode = drawMode
        (toolView as? DrawingView)?.setDrawMode(drawMode)
    }

    private fun setupDrawingView(drawingView: DrawingView) {
        currentBitmap?.let { bitmap ->
            drawingView.getImageView().setImageBitmap(bitmap)
        }
        // Set up drawing completion listener to commit to bitmap
        drawingView.setCompletionListener(object : DrawingCompletionListener {
            override fun onDrawingCompleted() {
                commitDrawing(drawingView.getDrawingCanvasView())
            }
        })

        // Re-apply the current paint settings
        drawingView.setPaintColor(currentPaintColor)
        drawingView.setPaintSize(currentPaintSize)
        drawingView.setPaintOpacity(currentPaintOpacity)
        drawingView.setDrawMode(currentDrawMode)
    }

    private fun setupCropView(cropView: CropView) {
        currentBitmap?.let { bitmap ->
            cropView.getImageView().setImageBitmap(bitmap)
        }
    }

    private fun setupAdjustView(adjustView: AdjustView) {
        currentBitmap?.let { bitmap ->
            adjustView.getImageView().setImageBitmap(bitmap)
        }
    }

    /**
     * Commit the drawing from the DrawingView to the bitmap
     * This is called when the user finishes a drawing action
     */
    fun commitDrawing(drawingCanvas: View) {
        currentBitmap?.let { bitmap ->
            val canvas = Canvas(bitmap)
            // Draw only the drawing canvas (not the image view sibling)
            drawingCanvas.draw(canvas)
            // Update the base image to show the modified bitmap
            baseImageView.setImageBitmap(bitmap)
        }
    }
}
