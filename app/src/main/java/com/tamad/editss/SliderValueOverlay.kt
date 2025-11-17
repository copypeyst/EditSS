package com.tamad.editss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.animation.ValueAnimator

/**
 * A floating overlay that displays slider values as the user moves the slider thumb.
 * This view is non-interactive and purely visual - it doesn't interfere with layout.
 */
class SliderValueOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentValue: String = ""
    private var thumbX: Float = 0f
    private var thumbY: Float = 0f
    private var isVisible: Boolean = false
    private var hideRunnable: Runnable? = null
    private var isBeingDragged: Boolean = false
    private var currentAlpha: Int = 255
    private var fadeOutAnimator: ValueAnimator? = null

    // Paint for the background bubble
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt() // Dark background
        alpha = 220 // Semi-transparent
    }

    // Paint for the text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // White text
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    // Paint for the border/stroke - uses teal accent color
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF03DAC5.toInt() // Teal accent (teal_200)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val bubbleRadius = 28f
    private val padding = 12f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isVisible && currentValue.isNotEmpty()) {
            // Update alpha for both paints based on fade animation
            bubblePaint.alpha = (220 * currentAlpha / 255f).toInt()
            strokePaint.alpha = currentAlpha
            textPaint.alpha = currentAlpha

            // Draw background bubble with rounded corners
            // Center the bubble horizontally on the thumb
            val bubbleLeft = thumbX - bubbleRadius - padding
            val bubbleTop = thumbY - bubbleRadius * 2.2f
            val bubbleRight = thumbX + bubbleRadius + padding
            val bubbleBottom = thumbY - bubbleRadius * 0.8f

            val bubbleRect = RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
            canvas.drawRoundRect(bubbleRect, 16f, 16f, bubblePaint)

            // Draw border
            canvas.drawRoundRect(bubbleRect, 16f, 16f, strokePaint)

            // Draw value text - center it properly
            val textYOffset = (bubbleTop + bubbleBottom) / 2 + 12f
            canvas.drawText(currentValue, thumbX, textYOffset, textPaint)
        }
    }

    /**
     * Call this when the user touches/starts dragging the slider
     * Shows the overlay immediately with the current value
     */
    fun onSliderTouched() {
        isBeingDragged = true
        currentAlpha = 255
        isVisible = true
        invalidate()
        
        // Cancel any pending hide or fade
        removeCallbacks(hideRunnable)
        fadeOutAnimator?.cancel()
    }

    /**
     * Update the overlay position and value based on the slider.
     * This is called during slider movement.
     */
    fun updateFromSlider(seekBar: SeekBar, value: Int, displayValue: String) {
        currentValue = displayValue
        
        // Get the thumb position on the slider using a more accurate method
        val thumb = seekBar.thumb
        if (thumb != null) {
            // Get slider position on screen
            val sliderLocation = IntArray(2)
            seekBar.getLocationOnScreen(sliderLocation)
            
            // Get overlay position on screen
            val overlayLocation = IntArray(2)
            this.getLocationOnScreen(overlayLocation)
            
            // The thumb bounds are relative to the SeekBar
            // Calculate the center of the thumb
            val thumb_x_in_seekbar = thumb.bounds.left + thumb.bounds.width() / 2
            
            // Convert to screen coordinates then to overlay coordinates
            thumbX = (sliderLocation[0] + thumb_x_in_seekbar - overlayLocation[0]).toFloat()
            
            // Position Y above the slider
            thumbY = (sliderLocation[1] - overlayLocation[1]).toFloat()
        }
        
        isVisible = true
        currentAlpha = 255
        invalidate()
        
        // Cancel any pending hide or fade
        removeCallbacks(hideRunnable)
        fadeOutAnimator?.cancel()
    }

    /**
     * Call this when the user releases the slider
     * Overlay will fade out over 1 second then hide
     */
    fun onSliderReleased() {
        isBeingDragged = false
        
        // Start fade out animation (1 second)
        fadeOutAnimator?.cancel()
        fadeOutAnimator = ValueAnimator.ofInt(255, 0).apply {
            duration = 1000 // 1 second fade
            addUpdateListener { animator ->
                currentAlpha = animator.animatedValue as Int
                invalidate()
            }
            start()
        }
        
        // Hide after animation completes
        removeCallbacks(hideRunnable)
        hideRunnable = Runnable { 
            isVisible = false
            invalidate()
        }
        postDelayed(hideRunnable!!, 1000)
    }

    /**
     * Immediately hide the overlay
     */
    fun hide() {
        isVisible = false
        isBeingDragged = false
        currentAlpha = 255
        removeCallbacks(hideRunnable)
        fadeOutAnimator?.cancel()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // This view is non-interactive, pass all events through
        return false
    }
}
