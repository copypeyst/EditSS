package com.tamad.editss

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent

class PenTool : DrawingTool {
    private val currentPath = Path()
    private var isDrawing = false

    override fun onTouchEvent(event: MotionEvent, paint: Paint): DrawingAction? {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                currentPath.reset()
                currentPath.moveTo(x, y)
                return null
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    currentPath.lineTo(x, y)
                }
                return null
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    isDrawing = false
                    val newPaint = Paint(paint)
                    val newPath = Path(currentPath)
                    currentPath.reset()
                    return DrawingAction(newPath, newPaint)
                }
                return null
            }
            MotionEvent.ACTION_CANCEL -> {
                // Cancel the drawing without returning an action
                isDrawing = false
                currentPath.reset()
                return null
            }
            else -> return null
        }
    }

    override fun onDraw(canvas: Canvas, paint: Paint) {
        if (isDrawing) {
            canvas.drawPath(currentPath, paint)
        }
    }
}
