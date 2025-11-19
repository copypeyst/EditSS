package com.tamad.editss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

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
    private var currentAlpha: Int = 255 // For fade animation
    private var isVisible: Boolean = false
    private var hideRunnable: Runnable? = null
    private var isBeingDragged: Boolean = false

    // Helper functions to convert DP/SP to Pixels
    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun spToPx(sp: Float): Float {
        return sp * resources.displayMetrics.scaledDensity
    }

    // Paint for the background bubble
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt() // Dark background
    }

    // Paint for the text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt() // White text
        textSize = spToPx(14f) // SCALED: Uses SP instead of raw pixels
        textAlign = Paint.Align.CENTER
    }

    // Paint for the border/stroke - uses teal accent color
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF03DAC5.toInt() // Teal accent (teal_200)
        strokeWidth = dpToPx(2f) // SCALED: Uses DP
        style = Paint.Style.STROKE
    }

    // SCALED dimensions
    private val bubbleRadius = dpToPx(28f)
    private val padding = dpToPx(12f)
    private val cornerRadius = dpToPx(8f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isVisible && currentValue.isNotEmpty()) {
            // Update paint alpha for fade effect
            bubblePaint.alpha = currentAlpha
            strokePaint.alpha = currentAlpha
            textPaint.alpha = currentAlpha

            // Draw background bubble with rounded corners
            // Center the bubble horizontally on the thumb (fixed alignment)
            val bubbleLeft = thumbX - bubbleRadius - padding
            val bubbleTop = thumbY - bubbleRadius * 2.2f
            val bubbleRight = thumbX + bubbleRadius + padding
            val bubbleBottom = thumbY - bubbleRadius * 0.8f

            val bubbleRect = RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
            
            // SCALED: Use converted corner radius
            canvas.drawRoundRect(bubbleRect, cornerRadius, cornerRadius, bubblePaint)

            // Draw border
            canvas.drawRoundRect(bubbleRect, cornerRadius, cornerRadius, strokePaint)

            // Draw value text - center it properly
            // Adjust text vertically to center it visually
            val textHeight = textPaint.descent() - textPaint.ascent()
            val textOffset = (textHeight / 2) - textPaint.descent()
            val bubbleCenterY = (bubbleTop + bubbleBottom) / 2
            
            canvas.drawText(currentValue, thumbX, bubbleCenterY + textOffset, textPaint)
        }
    }

    /**
     * Call this when the user touches/starts dragging the slider
     * Shows the overlay immediately with the current value
     */
    fun onSliderTouched(seekBar: SeekBar, currentProgress: Int, displayValue: String) {
        currentValue = displayValue
        currentAlpha = 220 // Full opacity
        isBeingDragged = true
        
        // Update position for touch event
        updateThumbPosition(seekBar)
        
        isVisible = true
        invalidate()
        
        // Cancel any pending hide
        removeCallbacks(hideRunnable)
    }

    /**
     * Update the overlay position and value based on the slider.
     * This is called during slider movement.
     */
    fun updateFromSlider(seekBar: SeekBar, value: Int, displayValue: String) {
        currentValue = displayValue
        currentAlpha = 220 // Keep full opacity while dragging
        
        updateThumbPosition(seekBar)
        
        isVisible = true
        isBeingDragged = true
        invalidate()
        
        // Cancel any pending hide
        removeCallbacks(hideRunnable)
    }

    /**
     * Helper function to calculate thumb position from SeekBar
     */
    private fun updateThumbPosition(seekBar: SeekBar) {
        // Get slider dimensions
        val sliderWidth = seekBar.width - seekBar.paddingLeft - seekBar.paddingRight
        val progress = seekBar.progress
        val max = seekBar.max
        
        // Calculate thumb position relative to slider start
        val thumbPosInSlider = (progress.toFloat() / max) * sliderWidth
        
        // Convert slider coordinates to overlay view coordinates
        val sliderLocation = IntArray(2)
        seekBar.getLocationOnScreen(sliderLocation)
        
        val overlayLocation = IntArray(2)
        this.getLocationOnScreen(overlayLocation)
        
        // Calculate absolute X position of thumb center
        thumbX = (sliderLocation[0] + seekBar.paddingLeft + thumbPosInSlider - overlayLocation[0]).toFloat()
        
        // Position Y above the slider
        thumbY = (sliderLocation[1] - overlayLocation[1]).toFloat()
    }

    /**
     * Call this when the user releases the slider
     * Overlay will fade out after 1 second
     */
    fun onSliderReleased() {
        isBeingDragged = false
        
        // Fade out after 1 second
        removeCallbacks(hideRunnable)
        hideRunnable = Runnable { startFadeOut() }
        postDelayed(hideRunnable!!, 1000)
    }

    /**
     * Start fade-out animation
     */
    private fun startFadeOut() {
        // Animate alpha from 220 to 0 over 300ms
        val startAlpha = 220
        val endAlpha = 0
        val duration = 300L
        val startTime = System.currentTimeMillis()
        
        val fadeRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                
                currentAlpha = (startAlpha * (1 - progress)).toInt()
                invalidate()
                
                if (progress < 1f) {
                    postDelayed(this, 16) // ~60fps
                } else {
                    isVisible = false
                    currentAlpha = 220 // Reset for next use
                    invalidate()
                }
            }
        }
        
        post(fadeRunnable)
    }

    /**
     * Immediately hide the overlay
     */
    fun hide() {
        isVisible = false
        isBeingDragged = false
        currentAlpha = 220
        removeCallbacks(hideRunnable)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // This view is non-interactive, pass all events through
        return false
    }
}