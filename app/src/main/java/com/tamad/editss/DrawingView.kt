package com.tamad.editss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity

enum class DrawMode {
    PEN,
    CIRCLE,
    SQUARE
}

class DrawingView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    private val imageView: ImageView
    private val drawingCanvas: DrawingCanvas

    init {
        imageView = ImageView(context)
        drawingCanvas = DrawingCanvas(context)

        addView(imageView)
        addView(drawingCanvas)
    }

    fun getImageView(): ImageView {
        return imageView
    }

    fun setupDrawingState(viewModel: EditViewModel) {
        drawingCanvas.setupDrawingState(viewModel)
    }

    fun setDrawMode(drawMode: DrawMode) {
        drawingCanvas.setDrawMode(drawMode)
    }

    private class DrawingCanvas(context: Context) : View(context) {

        private val paint = Paint()
        private val path = Path()
        private var currentDrawMode = DrawMode.PEN
        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f

        private var scaleFactor = 1f
        private var previousDistance = 0f
        private var midPointX = 0f
        private var midPointY = 0f

        private var activePointerId = MotionEvent.INVALID_POINTER_ID

        private var viewModel: EditViewModel? = null

        init {
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
        }

        fun setupDrawingState(viewModel: EditViewModel) {
            this.viewModel = viewModel
            
            // Subscribe to drawing state changes
            // Note: This will be called when the DrawingView is attached to an Activity
            // The Activity's lifecycleScope will handle the coroutine properly
            if (context is androidx.appcompat.app.AppCompatActivity) {
                val activity = context as androidx.appcompat.app.AppCompatActivity
                activity.lifecycleScope.launch {
                    viewModel.drawingState.collect { drawingState ->
                        paint.color = drawingState.color
                        paint.strokeWidth = drawingState.size
                        paint.alpha = drawingState.opacity
                        invalidate() // Redraw when state changes
                    }
                }
            }
        }

        fun setDrawMode(drawMode: DrawMode) {
            currentDrawMode = drawMode
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            canvas.save()
            canvas.scale(scaleFactor, scaleFactor, midPointX, midPointY)
            when (currentDrawMode) {
                DrawMode.PEN -> {
                    canvas.drawPath(path, paint)
                }
                DrawMode.CIRCLE -> {
                    val radius = Math.sqrt(Math.pow((startX - endX).toDouble(), 2.0) + Math.pow((startY - endY).toDouble(), 2.0)).toFloat()
                    canvas.drawCircle(startX, startY, radius, paint)
                }
                DrawMode.SQUARE -> {
                    canvas.drawRect(startX, startY, endX, endY, paint)
                }
            }
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Only start drawing if touch is within canvas bounds
                    if (isWithinCanvas(x, y)) {
                        activePointerId = event.getPointerId(0)
                        startX = x
                        startY = y
                        endX = x
                        endY = y
                        if (currentDrawMode == DrawMode.PEN) {
                            path.moveTo(x, y)
                        }
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Cancel drawing if a second finger is added mid-stroke
                    if (currentDrawMode == DrawMode.PEN) {
                        path.reset()
                    }
                    previousDistance = getPointerDistance(event)
                    midPointX = (event.getX(0) + event.getX(1)) / 2
                    midPointY = (event.getY(0) + event.getY(1)) / 2
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        // One-finger gesture for drawing/shape placement
                        endX = x
                        endY = y
                        if (currentDrawMode == DrawMode.PEN && isWithinCanvas(x, y)) {
                            path.lineTo(x, y)
                        }
                    } else if (event.pointerCount == 2) {
                        // Two-finger gesture for zoom/pan
                        val newDistance = getPointerDistance(event)
                        if (previousDistance != 0f) {
                            scaleFactor *= newDistance / previousDistance
                        }
                        previousDistance = newDistance
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    endX = x
                    endY = y
                    if (currentDrawMode != DrawMode.PEN) {
                        invalidate()
                    }
                    previousDistance = 0f
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                else -> return false
            }

            return true
        }

        private fun getPointerDistance(event: MotionEvent): Float {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            return Math.sqrt((x * x + y * y).toDouble()).toFloat()
        }
        
        private fun isWithinCanvas(x: Float, y: Float): Boolean {
            return x >= 0 && x <= width && y >= 0 && y <= height
        }
    }
}