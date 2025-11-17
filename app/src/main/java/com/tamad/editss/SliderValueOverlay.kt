package com.tamad.editss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import kotlin.math.abs

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

    // Paint for the border/stroke
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF66BB6A.toInt() // Green accent
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val bubbleRadius = 28f
    private val padding = 12f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isVisible && currentValue.isNotEmpty()) {
            // Draw background bubble with rounded corners
            val bubbleLeft = thumbX - bubbleRadius - padding
            val bubbleTop = thumbY - bubbleRadius * 2.2f
            val bubbleRight = thumbX + bubbleRadius + padding
            val bubbleBottom = thumbY - bubbleRadius * 0.8f

            val bubbleRect = RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
            canvas.drawRoundRect(bubbleRect, 16f, 16f, bubblePaint)

            // Draw border
            canvas.drawRoundRect(bubbleRect, 16f, 16f, strokePaint)

            // Draw value text
            val textYOffset = (bubbleTop + bubbleBottom) / 2 + 12f
            canvas.drawText(currentValue, thumbX, textYOffset, textPaint)
        }
    }

    /**
     * Update the overlay position and value based on the slider.
     * This is called during slider movement.
     */
    fun updateFromSlider(seekBar: SeekBar, value: Int, displayValue: String) {
        currentValue = displayValue
        
        // Get the thumb position on the slider
        val thumb = seekBar.thumb
        val thumbBounds = thumb?.bounds
        
        if (thumbBounds != null) {
            // Convert slider coordinates to overlay view coordinates
            val sliderLocation = IntArray(2)
            seekBar.getLocationOnScreen(sliderLocation)
            
            val overlayLocation = IntArray(2)
            this.getLocationOnScreen(overlayLocation)
            
            // Calculate thumb center X in overlay coordinates
            thumbX = (sliderLocation[0] + thumbBounds.centerX() - overlayLocation[0]).toFloat()
            
            // Position Y above the slider
            thumbY = (sliderLocation[1] - overlayLocation[1]).toFloat()
        }
        
        isVisible = true
        invalidate()
        
        // Hide after 1 second of no movement
        removeCallbacks(hideRunnable)
        hideRunnable = Runnable { hideOverlay() }
        postDelayed(hideRunnable!!, 1000)
    }

    /**
     * Hide the overlay with a fade-out animation
     */
    fun hideOverlay() {
        isVisible = false
        invalidate()
    }

    /**
     * Immediately hide the overlay
     */
    fun hide() {
        isVisible = false
        removeCallbacks(hideRunnable)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // This view is non-interactive, pass all events through
        return false
    }
}
