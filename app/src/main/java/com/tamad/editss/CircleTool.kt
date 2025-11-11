package com.tamad.editss

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import kotlin.math.sqrt

class CircleTool : DrawingTool {
    private val currentPath = Path()
    private var isDrawing = false
    private var startX = 0f
    private var startY = 0f

    override fun onTouchEvent(event: MotionEvent, paint: Paint): DrawingAction? {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                startX = x
                startY = y
                currentPath.reset()
                return null
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    currentPath.reset()
                    val radius = sqrt((startX - x).toDouble().pow(2.0) + (startY - y).toDouble().pow(2.0)).toFloat()
                    currentPath.addCircle(startX, startY, radius, Path.Direction.CW)
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
            else -> return null
        }
    }

    override fun onDraw(canvas: Canvas, paint: Paint) {
        if (isDrawing) {
            canvas.drawPath(currentPath, paint)
        }
    }
}
