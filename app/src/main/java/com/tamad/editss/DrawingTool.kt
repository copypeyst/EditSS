package com.tamad.editss

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent

interface DrawingTool {
    fun onTouchEvent(event: MotionEvent, paint: Paint): DrawingAction?
    fun onDraw(canvas: Canvas, paint: Paint)
}

abstract class BaseDrawingTool : DrawingTool {
    protected val currentPath = Path()
    protected var isDrawing = false
    protected var startX = 0f
    protected var startY = 0f

    override fun onTouchEvent(event: MotionEvent, paint: Paint): DrawingAction? {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                startX = x
                startY = y
                currentPath.reset()
                onTouchDown(x, y)
                return null
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    onTouchMove(x, y)
                }
                return null
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    isDrawing = false
                    val newPaint = Paint(paint)
                    val newPath = Path(currentPath)
                    currentPath.reset()
                    return onTouchUp(newPath, newPaint)
                }
                return null
            }
            MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                currentPath.reset()
                return null
            }
            else -> return null
        }
    }

    protected abstract fun onTouchDown(x: Float, y: Float)
    protected abstract fun onTouchMove(x: Float, y: Float)
    protected abstract fun onTouchUp(path: Path, paint: Paint): DrawingAction
}
