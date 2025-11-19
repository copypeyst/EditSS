package com.tamad.editss

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

class PenTool : BaseDrawingTool() {
    override fun onDraw(canvas: Canvas, paint: Paint) {
        if (isDrawing) {
            canvas.drawPath(currentPath, paint)
        }
    }

    override fun onTouchDown(x: Float, y: Float) {
        currentPath.moveTo(x, y)
    }

    override fun onTouchMove(x: Float, y: Float) {
        currentPath.lineTo(x, y)
    }

    override fun onTouchUp(path: Path, paint: Paint): DrawingAction {
        return DrawingAction(path, paint)
    }
}
