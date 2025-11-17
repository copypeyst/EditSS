package com.tamad.editss

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

class ValueSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    
    private val seekBar: SeekBar
    private val valueTextView: TextView
    private val valueBackground: View
    
    private var minValue = 0f
    private var maxValue = 100f
    private var currentValue = 0f
    private var isValueVisible = false
    
    // Value formatter function
    private var valueFormatter: ((Float) -> String)? = null
    
    // Value change listener
    private var valueChangedListener: ((Float) -> Unit)? = null
    
    init {
        // Inflate the layout
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.value_seekbar_layout, this, false)
        addView(view)
        
        // Find views
        seekBar = view.findViewById(R.id.seekbar)
        valueTextView = view.findViewById(R.id.value_text)
        valueBackground = view.findViewById(R.id.value_background)
        
        // Set up seek bar listener
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = minValue + (progress / seekBar.max.toFloat()) * (maxValue - minValue)
                    updateValue(value, true)
                    valueChangedListener?.invoke(value)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                showValue(true)
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                showValue(false)
            }
        })
        
        // Hide value by default
        hideValue()
    }
    
    fun setOnValueChangedListener(listener: (Float) -> Unit) {
        valueChangedListener = listener
    }
    
    fun setRange(min: Float, max: Float) {
        minValue = min
        maxValue = max
        updateSeekBarFromValue()
    }
    
    fun setValue(value: Float) {
        updateValue(value, false)
    }
    
    fun getValue(): Float = currentValue
    
    fun setValueFormatter(formatter: (Float) -> String) {
        valueFormatter = formatter
        updateValue(currentValue, false)
    }
    
    private fun updateValue(newValue: Float, animate: Boolean) {
        currentValue = newValue.coerceIn(minValue, maxValue)
        
        val text = valueFormatter?.invoke(currentValue) ?: currentValue.toString()
        valueTextView.text = text
        
        updateSeekBarFromValue()
        
        if (animate) {
            animateValueText()
        }
    }
    
    private fun updateSeekBarFromValue() {
        val progress = if (maxValue > minValue) {
            ((currentValue - minValue) / (maxValue - minValue) * seekBar.max).toInt()
        } else 0
        seekBar.progress = progress
    }
    
    private fun showValue(animate: Boolean) {
        isValueVisible = true
        valueTextView.visibility = View.VISIBLE
        valueBackground.visibility = View.VISIBLE
        
        if (animate) {
            animateValueText()
        }
        
        // Update position to appear above thumb
        post {
            updateValuePosition()
        }
    }
    
    private fun hideValue() {
        isValueVisible = false
        valueTextView.visibility = View.GONE
        valueBackground.visibility = View.GONE
    }
    
    private fun animateValueText() {
        valueTextView.scaleX = 0.8f
        valueTextView.scaleY = 0.8f
        
        val animator = ValueAnimator.ofFloat(0.8f, 1.0f)
        animator.duration = 150
        animator.addUpdateListener { animation ->
            val scale = animation.animatedValue as Float
            valueTextView.scaleX = scale
            valueTextView.scaleY = scale
        }
        animator.start()
    }
    
    private fun updateValuePosition() {
        if (!isValueVisible) return
        
        val thumbBounds = getThumbBounds()
        if (thumbBounds != null) {
            val centerX = thumbBounds.centerX()
            val valueWidth = valueTextView.width
            val valueHeight = valueTextView.height
            
            // Position above the thumb
            val left = (centerX - valueWidth / 2).coerceIn(0, width - valueWidth)
            val top = thumbBounds.top - valueHeight - 12 // 12dp padding for better spacing
            
            valueTextView.x = left.toFloat()
            valueTextView.y = top.toFloat()
            valueBackground.x = left.toFloat()
            valueBackground.y = top.toFloat()
        }
    }
    
    private fun getThumbBounds(): Rect? {
        try {
            val thumb = seekBar.thumb ?: return null
            val bounds = Rect()
            thumb.getBounds(bounds)
            
            // Adjust bounds to get actual thumb position considering track height
            val trackHeight = seekBar.height
            val thumbHeight = bounds.height()
            val thumbTop = (trackHeight - thumbHeight) / 2
            val adjustedBounds = Rect(bounds.left, thumbTop, bounds.right, thumbTop + thumbHeight)
            
            return adjustedBounds
        } catch (e: Exception) {
            return null
        }
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isValueVisible) {
            post {
                updateValuePosition()
            }
        }
    }
}