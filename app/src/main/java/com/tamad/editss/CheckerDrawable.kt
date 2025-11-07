package com.tamad.editss

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class CheckerDrawable : Drawable() {
    private val paint = Paint()
    private val squareSize = 30

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val width = bounds.width()
        val height = bounds.height()

        for (i in 0 until (width / squareSize + 1)) {
            for (j in 0 until (height / squareSize + 1)) {
                paint.color = if ((i + j) % 2 == 0) Color.rgb(180, 180, 180) else Color.rgb(211, 211, 211)
                canvas.drawRect(
                    (i * squareSize).toFloat(),
                    (j * squareSize).toFloat(),
                    ((i + 1) * squareSize).toFloat(),
                    ((j + 1) * squareSize).toFloat(),
                    paint
                )
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int {
        return PixelFormat.OPAQUE
    }
}
