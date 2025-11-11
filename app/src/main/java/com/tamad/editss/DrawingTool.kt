package com.tamad.editss

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent

interface DrawingTool {
    fun onTouchEvent(event: MotionEvent, paint: Paint): DrawingAction?
    fun onDraw(canvas: Canvas, paint: Paint)
}
