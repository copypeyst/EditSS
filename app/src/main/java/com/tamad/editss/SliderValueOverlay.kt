package com.tamad.editss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.core.content.ContextCompat

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

    // Paint for the text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = spToPx(10f) 
        textAlign = Paint.Align.CENTER
    }

    // Paint for the outline
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF03DAC5.toInt() 
        strokeWidth = dpToPx(0.5f) 
        style = Paint.Style.STROKE
    }

    private val horizontalPadding = dpToPx(4f) 
    private val verticalPadding = dpToPx(4f)
    
    private val cornerRadius = dpToPx(4f) 

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isVisible && currentValue.isNotEmpty()) {
            bubblePaint.alpha = currentAlpha
            strokePaint.alpha = currentAlpha
            textPaint.alpha = currentAlpha

            val textWidth = textPaint.measureText(currentValue)
            
            val fontMetrics = textPaint.fontMetrics

            val textHeight = fontMetrics.descent - fontMetrics.ascent 

            val boxWidth = textWidth + (horizontalPadding * 2)
            val boxHeight = textHeight + (verticalPadding * 2)

            val gapAboveThumb = dpToPx(8f) // Distance from finger
            
            val bubbleBottom = thumbY - gapAboveThumb
            val bubbleTop = bubbleBottom - boxHeight
            
            val bubbleLeft = thumbX - (boxWidth / 2)
            val bubbleRight = thumbX + (boxWidth / 2)

            val bubbleRect = RectF(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom)
            
            canvas.drawRoundRect(bubbleRect, cornerRadius, cornerRadius, bubblePaint)
            canvas.drawRoundRect(bubbleRect, cornerRadius, cornerRadius, strokePaint)

            val bubbleCenterY = (bubbleTop + bubbleBottom) / 2
            val textOffset = (textPaint.descent() + textPaint.ascent()) / 2
            
            canvas.drawText(currentValue, thumbX, bubbleCenterY - textOffset, textPaint)
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