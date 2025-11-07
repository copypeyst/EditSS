package com.tamad.editss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paint = Paint()
    private val currentPath = Path()

    private var baseBitmap: Bitmap? = null
    private var paths = listOf<DrawingAction>()

    private var currentDrawMode = DrawMode.PEN
    private var startX = 0f
    private var startY = 0f

    private var isDrawing = false

    var onNewPath: ((DrawingAction) -> Unit)? = null

    init {
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
    }

    fun setDrawingState(drawingState: DrawingState) {
        paint.color = drawingState.color
        paint.strokeWidth = drawingState.size
        paint.alpha = drawingState.opacity
        currentDrawMode = drawingState.drawMode
    }

    fun setBitmap(bitmap: Bitmap?) {
        baseBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        invalidate()
    }

    fun setPaths(paths: List<DrawingAction>) {
        this.paths = paths
        invalidate()
    }

    fun getDrawing(): Bitmap? {
        if (baseBitmap == null) return null
        val resultBitmap = baseBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        val scaleX = resultBitmap.width / width.toFloat()
        val scaleY = resultBitmap.height / height.toFloat()

        for (action in paths) {
            val scaledPath = Path()
            action.path.transform(android.graphics.Matrix().apply { postScale(scaleX, scaleY) }, scaledPath)
            canvas.drawPath(scaledPath, action.paint)
        }

        return resultBitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        baseBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        for (action in paths) {
            canvas.drawPath(action.path, action.paint)
        }

        if (isDrawing) {
            canvas.drawPath(currentPath, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                startX = x
                startY = y
                currentPath.reset()
                if (currentDrawMode == DrawMode.PEN) {
                    currentPath.moveTo(x, y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing) return false
                if (currentDrawMode == DrawMode.PEN) {
                    currentPath.lineTo(x, y)
                } else {
                    currentPath.reset()
                    when (currentDrawMode) {
                        DrawMode.CIRCLE -> {
                            val radius = Math.sqrt(Math.pow((startX - x).toDouble(), 2.0) + Math.pow((startY - y).toDouble(), 2.0)).toFloat()
                            currentPath.addCircle(startX, startY, radius, Path.Direction.CW)
                        }
                        DrawMode.SQUARE -> {
                            currentPath.addRect(startX, startY, x, y, Path.Direction.CW)
                        }
                        else -> {}
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!isDrawing) return false
                isDrawing = false

                val newPaint = Paint(paint)
                val newPath = Path(currentPath)
                onNewPath?.invoke(DrawingAction(newPath, newPaint))

                currentPath.reset()
                invalidate()
            }
            else -> return false
        }
        return true
    }
}