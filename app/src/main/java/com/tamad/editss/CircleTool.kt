package com.tamad.editss

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.pow
import kotlin.math.sqrt

class CircleTool : BaseDrawingTool() {
    override fun onDraw(canvas: Canvas, paint: Paint) {
        if (isDrawing) {
            canvas.drawPath(currentPath, paint)
        }
    }

    override fun onTouchDown(x: Float, y: Float) {
        // Nothing needed for circle on down event
    }

    override fun onTouchMove(x: Float, y: Float) {
        currentPath.reset()
        val radius = sqrt((startX - x).toDouble().pow(2.0) + (startY - y).toDouble().pow(2.0)).toFloat()
        currentPath.addCircle(startX, startY, radius, Path.Direction.CW)
    }

    override fun onTouchUp(path: Path, paint: Paint): DrawingAction {
        return DrawingAction(path, paint)
    }
}
