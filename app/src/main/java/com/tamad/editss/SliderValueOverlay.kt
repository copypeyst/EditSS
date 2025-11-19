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

class SliderValueOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentValue: String = ""
    private var thumbX: Float = 0f
    private var thumbY: Float = 0f
    private var currentAlpha: Int = 255 
    private var isVisible: Boolean = false
    private var hideRunnable: Runnable? = null
    private var isBeingDragged: Boolean = false

    // Helper functions
    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun spToPx(sp: Float): Float {
        return sp * resources.displayMetrics.scaledDensity
    }

    // Paint for the background bubble
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt() 
    }

    // Paint for the text (Reduced to 10sp for compact look)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = spToPx(10f) 
        textAlign = Paint.Align.CENTER
    }

    // Paint for the border/stroke (Reduced stroke width)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF03DAC5.toInt() 
        strokeWidth = dpToPx(1f) 
        style = Paint.Style.STROKE
    }

    // Drastically reduced dimensions
    private val bubbleRadius = dpToPx(14f) // Smaller bubble
    private val padding = dpToPx(8f)     // Less horizontal padding
    private val cornerRadius = dpToPx(4f) // Tighter corners

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isVisible && currentValue.isNotEmpty()) {
            bubblePaint.alpha = currentAlpha
            strokePaint.alpha = currentAlpha
            textPaint.alpha = currentAlpha

            // Position Calculation:
            // We draw the bubble ABOVE the thumb.
            // thumbY is the center of the slider track.
            
            // Distance above the thumb center (Gap)
            val gapAboveThumb = dpToPx(20f) 

            val bubbleHeight = bubbleRadius * 2 // roughly 28dp height
            
            val bubbleBottom = thumbY - gapAboveThumb
            val bubbleTop = bubbleBottom - bubbleHeight
            
            // Horizontal centering
            val bubbleLeft = thumbX - bubbleRadius - (padding / 2)
            val bubbleRight = thumbX + bubbleRadius + (padding / 2)

            val bubbleRect = RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
            
            canvas.drawRoundRect(bubbleRect, cornerRadius, cornerRadius, bubblePaint)
            canvas.drawRoundRect(bubbleRect, cornerRadius, cornerRadius, strokePaint)

            // Center Text
            val textHeight = textPaint.descent() - textPaint.ascent()
            val textOffset = (textHeight / 2) - textPaint.descent()
            val bubbleCenterY = (bubbleTop + bubbleBottom) / 2
            
            canvas.drawText(currentValue, thumbX, bubbleCenterY + textOffset, textPaint)
        }
    }

    fun onSliderTouched(seekBar: SeekBar, currentProgress: Int, displayValue: String) {
        currentValue = displayValue
        currentAlpha = 220 
        isBeingDragged = true
        updateThumbPosition(seekBar)
        isVisible = true
        invalidate()
        removeCallbacks(hideRunnable)
    }

    fun updateFromSlider(seekBar: SeekBar, value: Int, displayValue: String) {
        currentValue = displayValue
        currentAlpha = 220 
        updateThumbPosition(seekBar)
        isVisible = true
        isBeingDragged = true
        invalidate()
        removeCallbacks(hideRunnable)
    }

    private fun updateThumbPosition(seekBar: SeekBar) {
        val sliderWidth = seekBar.width - seekBar.paddingLeft - seekBar.paddingRight
        val progress = seekBar.progress
        val max = seekBar.max
        
        val thumbPosInSlider = (progress.toFloat() / max) * sliderWidth
        
        val sliderLocation = IntArray(2)
        seekBar.getLocationOnScreen(sliderLocation)
        
        val overlayLocation = IntArray(2)
        this.getLocationOnScreen(overlayLocation)
        
        thumbX = (sliderLocation[0] + seekBar.paddingLeft + thumbPosInSlider - overlayLocation[0]).toFloat()
        thumbY = (sliderLocation[1] - overlayLocation[1]).toFloat()
    }

    fun onSliderReleased() {
        isBeingDragged = false
        removeCallbacks(hideRunnable)
        hideRunnable = Runnable { startFadeOut() }
        postDelayed(hideRunnable!!, 1000)
    }

    private fun startFadeOut() {
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
                    postDelayed(this, 16) 
                } else {
                    isVisible = false
                    currentAlpha = 220 
                    invalidate()
                }
            }
        }
        post(fadeRunnable)
    }

    fun hide() {
        isVisible = false
        isBeingDragged = false
        currentAlpha = 220
        removeCallbacks(hideRunnable)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return false
    }
}