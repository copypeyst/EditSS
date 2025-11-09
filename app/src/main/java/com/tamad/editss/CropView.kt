package com.tamad.editss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

enum class CropMode {
    FREEFORM,
    SQUARE,
    PORTRAIT,
    LANDSCAPE
}

class CropView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    private val imageView: ImageView
    private val cropOverlay: CropOverlay

    init {
        imageView = ImageView(context)
        cropOverlay = CropOverlay(context)

        addView(imageView)
        addView(cropOverlay)

        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            val drawable = imageView.drawable
            if (drawable != null) {
                val imageMatrix = imageView.imageMatrix
                val drawableRect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
                imageMatrix.mapRect(drawableRect)
                cropOverlay.setImageBounds(drawableRect)
            }
        }
    }

    fun getImageView(): ImageView {
        return imageView
    }

    fun setCropMode(cropMode: CropMode) {
        cropOverlay.setCropMode(cropMode)
    }

    fun getCroppedRect(): RectF {
        return cropOverlay.getCroppedRect()
    }

    private class CropOverlay(context: Context) : View(context) {

        private val paint = Paint()
        private val cornerPaint = Paint()
        private var cropRect = RectF()
        private var currentCropMode = CropMode.FREEFORM
        private val matrixValues = FloatArray(9)
        private var imageMatrix = Matrix()
        private var imageBounds = RectF() // Added to store image bounds

        private enum class CropState {
            IDLE,
            DRAGGING_CORNER,
            DRAGGING_CENTER,
            CREATING
        }

        private var currentCropState = CropState.IDLE
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private val touchTolerance = 40f // Tolerance for corner/edge detection

        fun setImageBounds(bounds: RectF) { // Added method to set image bounds
            imageBounds.set(bounds)
            invalidate()
        }

        init {
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.color = android.graphics.Color.WHITE
            paint.strokeWidth = 3f

            cornerPaint.isAntiAlias = true
            cornerPaint.style = Paint.Style.FILL
            cornerPaint.color = android.graphics.Color.WHITE
        }

        fun setCropMode(cropMode: CropMode) {
            currentCropMode = cropMode
            cropRect = RectF()
            invalidate()
        }

        fun getCroppedRect(): RectF {
            return cropRect
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(cropRect, paint)

            // Draw corners
            val cornerSize = 20f
            canvas.drawRect(cropRect.left, cropRect.top, cropRect.left + cornerSize, cropRect.top + cornerSize, cornerPaint)
            canvas.drawRect(cropRect.right - cornerSize, cropRect.top, cropRect.right, cropRect.top + cornerSize, cornerPaint)
            canvas.drawRect(cropRect.left, cropRect.bottom - cornerSize, cropRect.left + cornerSize, cropRect.bottom, cornerPaint)
            canvas.drawRect(cropRect.right - cornerSize, cropRect.bottom - cornerSize, cropRect.right, cropRect.bottom, cornerPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cropRect.left = x
                    cropRect.top = y
                    cropRect.right = x
                    cropRect.bottom = y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    updateCropRect(x, y)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    invalidate()
                }
                else -> return false
            }

            return true
        }

        private var imageBounds = RectF() // Added to store image bounds

        private enum class CropState {
            IDLE,
            DRAGGING_CORNER,
            DRAGGING_CENTER,
            CREATING
        }

        private enum class HitRegion {
            NONE,
            CENTER,
            TOP_LEFT,
            TOP_RIGHT,
            BOTTOM_LEFT,
            BOTTOM_RIGHT,
            LEFT,
            TOP,
            RIGHT,
            BOTTOM
        }

        private var currentCropState = CropState.IDLE
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private val touchTolerance = 40f // Tolerance for corner/edge detection

        private var hitRegion: HitRegion = HitRegion.NONE

        // Variables to store initial crop rect for move/resize operations
        private val initialCropRect = RectF()

        private fun getHitRegion(x: Float, y: Float): HitRegion {
            val left = cropRect.left
            val top = cropRect.top
            val right = cropRect.right
            val bottom = cropRect.bottom

            val hitLeft = x >= left - touchTolerance && x <= left + touchTolerance
            val hitTop = y >= top - touchTolerance && y <= top + touchTolerance
            val hitRight = x >= right - touchTolerance && x <= right + touchTolerance
            val hitBottom = y >= bottom - touchTolerance && y <= bottom + touchTolerance

            return when {
                hitLeft && hitTop -> HitRegion.TOP_LEFT
                hitRight && hitTop -> HitRegion.TOP_RIGHT
                hitLeft && hitBottom -> HitRegion.BOTTOM_LEFT
                hitRight && hitBottom -> HitRegion.BOTTOM_RIGHT
                hitLeft -> HitRegion.LEFT
                hitTop -> HitRegion.TOP
                hitRight -> HitRegion.RIGHT
                hitBottom -> HitRegion.BOTTOM
                cropRect.contains(x, y) -> HitRegion.CENTER
                else -> HitRegion.NONE
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    lastTouchX = x
                    lastTouchY = y
                    hitRegion = getHitRegion(x, y)
                    initialCropRect.set(cropRect) // Store initial crop rect for calculations

                    when (hitRegion) {
                        HitRegion.NONE -> {
                            currentCropState = CropState.CREATING
                            // Start a new crop rect, constrained by imageBounds
                            cropRect.set(x, y, x, y)
                            cropRect.intersect(imageBounds) // Ensure it starts within bounds
                        }
                        HitRegion.CENTER -> {
                            currentCropState = CropState.DRAGGING_CENTER
                        }
                        else -> { // Any corner or edge
                            currentCropState = CropState.DRAGGING_CORNER
                        }
                    }
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex == -1) return false

                    val currentX = event.getX(pointerIndex)
                    val currentY = event.getY(pointerIndex)

                    val dx = currentX - lastTouchX
                    val dy = currentY - lastTouchY

                    when (currentCropState) {
                        CropState.CREATING -> {
                            updateCropRectOnCreate(currentX, currentY)
                        }
                        CropState.DRAGGING_CENTER -> {
                            updateCropRectOnMove(dx, dy)
                        }
                        CropState.DRAGGING_CORNER -> {
                            updateCropRectOnResize(currentX, currentY)
                        }
                        CropState.IDLE -> {
                            // Do nothing
                        }
                    }

                    lastTouchX = currentX
                    lastTouchY = currentY
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    currentCropState = CropState.IDLE
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    hitRegion = HitRegion.NONE
                    invalidate()
                    return true
                }
            }
            return false
        }

        private fun updateCropRectOnCreate(x: Float, y: Float) {
            // Ensure the new crop rect is always within imageBounds
            val newRight = x.coerceIn(imageBounds.left, imageBounds.right)
            val newBottom = y.coerceIn(imageBounds.top, imageBounds.bottom)

            cropRect.right = newRight
            cropRect.bottom = newBottom

            // Apply aspect ratio to the current cropRect
            applyAspectRatio(cropRect)
            cropRect.intersect(imageBounds) // Final check to ensure it's within bounds
        }

        private fun updateCropRectOnMove(dx: Float, dy: Float) {
            val newLeft = cropRect.left + dx
            val newTop = cropRect.top + dy
            val newRight = cropRect.right + dx
            val newBottom = cropRect.bottom + dy

            // Check if the new position is within image bounds
            if (newLeft >= imageBounds.left && newTop >= imageBounds.top &&
                newRight <= imageBounds.right && newBottom <= imageBounds.bottom) {
                cropRect.offset(dx, dy)
            } else {
                // Adjust dx, dy to keep the cropRect within bounds
                val constrainedDx = dx.coerceIn(imageBounds.left - cropRect.left, imageBounds.right - cropRect.right)
                val constrainedDy = dy.coerceIn(imageBounds.top - cropRect.top, imageBounds.bottom - cropRect.bottom)
                cropRect.offset(constrainedDx, constrainedDy)
            }
        }

        private fun updateCropRectOnResize(x: Float, y: Float) {
            // Use initialCropRect as a reference for resizing
            val currentLeft = initialCropRect.left
            val currentTop = initialCropRect.top
            val currentRight = initialCropRect.right
            val currentBottom = initialCropRect.bottom

            var newLeft = cropRect.left
            var newTop = cropRect.top
            var newRight = cropRect.right
            var newBottom = cropRect.bottom

            when (hitRegion) {
                HitRegion.TOP_LEFT -> {
                    newLeft = x
                    newTop = y
                }
                HitRegion.TOP_RIGHT -> {
                    newRight = x
                    newTop = y
                }
                HitRegion.BOTTOM_LEFT -> {
                    newLeft = x
                    newBottom = y
                }
                HitRegion.BOTTOM_RIGHT -> {
                    newRight = x
                    newBottom = y
                }
                HitRegion.LEFT -> {
                    newLeft = x
                }
                HitRegion.TOP -> {
                    newTop = y
                }
                HitRegion.RIGHT -> {
                    newRight = x
                }
                HitRegion.BOTTOM -> {
                    newBottom = y
                }
                else -> { /* Should not happen in DRAGGING_CORNER state */ }
            }

            // Create a temporary rect for calculations
            val tempRect = RectF(newLeft, newTop, newRight, newBottom)

            // Apply aspect ratio to the temporary rect
            applyAspectRatio(tempRect)

            // Ensure the cropRect stays within imageBounds
            tempRect.intersect(imageBounds)

            // Update cropRect with the new, constrained values
            cropRect.set(tempRect)
        }

        private fun applyAspectRatio(rect: RectF) {
            if (currentCropMode == CropMode.FREEFORM) {
                return
            }

            val aspectRatio = when (currentCropMode) {
                CropMode.SQUARE -> 1f
                CropMode.PORTRAIT -> 9f / 16f
                CropMode.LANDSCAPE -> 16f / 9f
                else -> return // Should not happen
            }

            var newWidth = rect.width()
            var newHeight = rect.height()

            // Calculate new dimensions based on aspect ratio
            if (newWidth / newHeight > aspectRatio) {
                newWidth = newHeight * aspectRatio
            } else {
                newHeight = newWidth / aspectRatio
            }

            // Adjust the rect based on the hitRegion
            when (hitRegion) {
                HitRegion.TOP_LEFT -> {
                    rect.left = rect.right - newWidth
                    rect.top = rect.bottom - newHeight
                }
                HitRegion.TOP_RIGHT -> {
                    rect.right = rect.left + newWidth
                    rect.top = rect.bottom - newHeight
                }
                HitRegion.BOTTOM_LEFT -> {
                    rect.left = rect.right - newWidth
                    rect.bottom = rect.top + newHeight
                }
                HitRegion.BOTTOM_RIGHT -> {
                    rect.right = rect.left + newWidth
                    rect.bottom = rect.top + newHeight
                }
                HitRegion.LEFT -> {
                    rect.left = rect.right - newWidth
                }
                HitRegion.TOP -> {
                    rect.top = rect.bottom - newHeight
                }
                HitRegion.RIGHT -> {
                    rect.right = rect.left + newWidth
                }
                HitRegion.BOTTOM -> {
                    rect.bottom = rect.top + newHeight
                }
                HitRegion.CENTER, HitRegion.NONE -> {
                    // For center drag or initial creation, expand from top-left
                    rect.right = rect.left + newWidth
                    rect.bottom = rect.top + newHeight
                }
            }
        }
    }
}