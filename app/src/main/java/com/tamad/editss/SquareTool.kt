package com.tamad.editss

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

class SquareTool : BaseDrawingTool() {
    override fun onDraw(canvas: Canvas, paint: Paint) {
        if (isDrawing) {
            canvas.drawPath(currentPath, paint)
        }
    }

    override fun onTouchDown(x: Float, y: Float) {
        // Nothing needed for square on down event
    }

    override fun onTouchMove(x: Float, y: Float) {
        currentPath.reset()
        currentPath.addRect(startX, startY, x, y, Path.Direction.CW)
    }

    override fun onTouchUp(path: Path, paint: Paint): DrawAction {
        return DrawAction(path, paint)
    }
}
