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

enum class DrawMode {
    PEN,
    CIRCLE,
    SQUARE
}

class DrawingView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

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

    fun setPaintColor(color: Int) {
        drawingCanvas.setPaintColor(color)
    }

    fun setPaintSize(size: Float) {
        drawingCanvas.setPaintSize(size)
    }

    fun setPaintOpacity(opacity: Int) {
        drawingCanvas.setPaintOpacity(opacity)
    }

    fun setDrawMode(drawMode: DrawMode) {
        drawingCanvas.setDrawMode(drawMode)
    }

    fun setEditViewModel(viewModel: EditViewModel) {
        drawingCanvas.setEditViewModel(viewModel)
    }

    fun clearDrawing() {
        drawingCanvas.clearCanvas()
    }

    fun getCurrentPath(): Path? {
        return drawingCanvas.getCurrentPath()
    }

    fun getCurrentPaint(): Paint? {
        return drawingCanvas.getCurrentPaint()
    }

    fun getCurrentMode(): DrawMode {
        return drawingCanvas.getCurrentMode()
    }

    fun replayActions(actions: List<EditAction.Draw>) {
        drawingCanvas.replayActions(actions)
    }

    fun undo() {
        drawingCanvas.undo()
    }

    fun redo(path: Path, paint: Paint) {
        drawingCanvas.redo(path, paint)
    }

    private inner class DrawingCanvas(context: Context) : View(context) {

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

        private var editViewModel: EditViewModel? = null
        private val actionPaths = mutableListOf<Pair<Path, Paint>>()

        fun setEditViewModel(viewModel: EditViewModel) {
            editViewModel = viewModel
        }

        fun clearCanvas() {
            path.reset()
            actionPaths.clear()
            invalidate()
        }

        fun getCurrentPath(): Path? {
            return if (actionPaths.isNotEmpty()) actionPaths.last().first else null
        }

        fun getCurrentPaint(): Paint? {
            return if (actionPaths.isNotEmpty()) actionPaths.last().second else null
        }

        fun getCurrentMode(): DrawMode {
            return currentDrawMode
        }

        fun replayActions(actions: List<EditAction.Draw>) {
            path.reset()
            actionPaths.clear()
            for (action in actions) {
                val paintCopy = Paint(action.paint)
                val pathCopy = Path(action.path)
                path.addPath(pathCopy)
                actionPaths.add(Pair(pathCopy, paintCopy))
            }
            invalidate()
        }

        fun undo() {
            if (actionPaths.isNotEmpty()) {
                actionPaths.removeLast()
                path.reset()
                for ((pathData, _) in actionPaths) {
                    path.addPath(pathData)
                }
                invalidate()
            }
        }

        fun redo(pathData: Path, paintData: Paint) {
            val paintCopy = Paint(paintData)
            val pathCopy = Path(pathData)
            path.addPath(pathCopy)
            actionPaths.add(Pair(pathCopy, paintCopy))
            invalidate()
        }

        fun setPaintColor(color: Int) {
            paint.color = color
        }

        fun setPaintSize(size: Float) {
            paint.strokeWidth = size
        }

        fun setPaintOpacity(opacity: Int) {
            paint.alpha = opacity
        }

        fun setDrawMode(drawMode: DrawMode) {
            currentDrawMode = drawMode
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.scale(scaleFactor, scaleFactor, midPointX, midPointY)
            for ((pathData, paintData) in actionPaths) {
                when (currentDrawMode) {
                    DrawMode.PEN -> canvas.drawPath(pathData, paintData)
                    else -> {
                        val bounds = android.graphics.RectF()
                        pathData.computeBounds(bounds, true)
                        when (currentDrawMode) {
                            DrawMode.CIRCLE -> {
                                val radius = Math.sqrt(Math.pow((bounds.left - bounds.right).toDouble(), 2.0) + Math.pow((bounds.top - bounds.bottom).toDouble(), 2.0)).toFloat() / 2
                                val centerX = (bounds.left + bounds.right) / 2
                                val centerY = (bounds.top + bounds.bottom) / 2
                                canvas.drawCircle(centerX, centerY, radius, paintData)
                            }
                            DrawMode.SQUARE -> canvas.drawRect(bounds, paintData)
                            else -> {}
                        }
                    }
                }
            }
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (currentDrawMode == DrawMode.PEN) {
                        finalizeCurrentDrawing()
                    }
                    previousDistance = getPointerDistance(event)
                    midPointX = (event.getX(0) + event.getX(1)) / 2
                    midPointY = (event.getY(0) + event.getY(1)) / 2
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        endX = x
                        endY = y
                        if (currentDrawMode == DrawMode.PEN) {
                            path.lineTo(x, y)
                        }
                    } else if (event.pointerCount == 2) {
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
                        when (currentDrawMode) {
                            DrawMode.CIRCLE -> {
                                val radius = Math.sqrt(Math.pow((startX - endX).toDouble(), 2.0) + Math.pow((startY - endY).toDouble(), 2.0)).toFloat()
                                val circlePath = Path().apply {
                                    addCircle(startX, startY, radius, Path.Direction.CW)
                                }
                                val paintCopy = Paint(paint)
                                actionPaths.add(Pair(circlePath, paintCopy))
                            }
                            DrawMode.SQUARE -> {
                                val rectPath = Path().apply {
                                    addRect(startX, startY, endX, endY, Path.Direction.CW)
                                }
                                val paintCopy = Paint(paint)
                                actionPaths.add(Pair(rectPath, paintCopy))
                            }
                            else -> {}
                        }
                    } else {
                        finalizeCurrentDrawing()
                    }
                    previousDistance = 0f
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                else -> return false
            }

            return true
        }

        private fun finalizeCurrentDrawing() {
            if (currentDrawMode == DrawMode.PEN) {
                val paintCopy = Paint(paint)
                val pathCopy = Path(path)
                actionPaths.add(Pair(pathCopy, paintCopy))
                editViewModel?.pushAction(EditAction.Draw(pathCopy, paintCopy, currentDrawMode))
                path.reset()
            }
        }

        private fun getPointerDistance(event: MotionEvent): Float {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            return Math.sqrt((x * x + y * y).toDouble()).toFloat()
        }
    }
}