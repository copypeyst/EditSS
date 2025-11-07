package com.tamad.editss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.tamad.editss.DrawMode

import android.graphics.RectF

class DrawingView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paint = Paint()
    private val currentPath = Path()

    private var baseBitmap: Bitmap? = null
    private var paths = listOf<DrawingAction>()
    private val imageMatrix = android.graphics.Matrix()
    private val imageBounds = RectF()

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
        background = resources.getDrawable(R.drawable.outer_bounds, null)

        updateImageMatrix()
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

        val inverseMatrix = android.graphics.Matrix()
        imageMatrix.invert(inverseMatrix)

        for (action in paths) {
            val transformedPath = Path()
            action.path.transform(inverseMatrix, transformedPath)
            canvas.drawPath(transformedPath, action.paint)
        }

        return resultBitmap
    }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateImageMatrix()
        }
    
         override fun onDraw(canvas: Canvas) {        super.onDraw(canvas)
        baseBitmap?.let {
            canvas.save()
            canvas.clipRect(imageBounds)
            if (it.hasAlpha()) {
                val checker = CheckerDrawable()
                val rect = android.graphics.Rect()
                imageBounds.roundOut(rect) // Convert RectF to Rect
                checker.bounds = rect
                checker.draw(canvas)
            }
            canvas.drawBitmap(it, imageMatrix, null)
        }

        for (action in paths) {
            canvas.drawPath(action.path, action.paint)
        }

        if (isDrawing) {
            canvas.drawPath(currentPath, paint)
        }

        baseBitmap?.let {
            canvas.restore()
        }
    }

    private fun updateImageMatrix() {
        baseBitmap?.let {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val bitmapWidth = it.width.toFloat()
            val bitmapHeight = it.height.toFloat()

            val scale: Float
            var dx = 0f
            var dy = 0f

            if (bitmapWidth / viewWidth > bitmapHeight / viewHeight) {
                scale = viewWidth / bitmapWidth
                dy = (viewHeight - bitmapHeight * scale) * 0.5f
            } else {
                scale = viewHeight / bitmapHeight
                dx = (viewWidth - bitmapWidth * scale) * 0.5f
            }

            imageMatrix.setScale(scale, scale)
            imageMatrix.postTranslate(dx, dy)

            imageBounds.set(0f, 0f, bitmapWidth, bitmapHeight)
            imageMatrix.mapRect(imageBounds)
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