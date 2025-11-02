package com.tamad.editss

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.FrameLayout

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var savePanel: View
    private lateinit var toolOptionsLayout: LinearLayout
    private lateinit var drawOptionsLayout: LinearLayout
    private lateinit var cropOptionsLayout: LinearLayout
    private lateinit var adjustOptionsLayout: LinearLayout

    private var currentActiveTool: ImageView? = null
    private var currentDrawMode: ImageView? = null
    private var currentCropMode: ImageView? = null
    private var currentColor: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find UI elements
        rootLayout = findViewById(R.id.root_layout)
        val buttonSave: ImageView = findViewById(R.id.button_save)
        val toolDraw: ImageView = findViewById(R.id.tool_draw)
        val toolCrop: ImageView = findViewById(R.id.tool_crop)
        val toolAdjust: ImageView = findViewById(R.id.tool_adjust)

        savePanel = findViewById(R.id.save_panel)
        toolOptionsLayout = findViewById(R.id.tool_options)
        drawOptionsLayout = findViewById(R.id.draw_options)
        cropOptionsLayout = findViewById(R.id.crop_options)
        adjustOptionsLayout = findViewById(R.id.adjust_options)

        // Save Panel Logic
        buttonSave.setOnClickListener {
            savePanel.visibility = if (savePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            // Hide other tool options if Save panel is shown
            toolOptionsLayout.visibility = View.GONE
            currentActiveTool = null // No tool is active when save panel is open
        }

        // Close save panel when tapping outside it
        rootLayout.setOnTouchListener { v, event ->
            if (savePanel.visibility == View.VISIBLE) {
                val outRect = android.graphics.Rect()
                savePanel.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    savePanel.visibility = View.GONE
                    return@setOnTouchListener true
                }
            }
            false
        }

        // Tool Buttons Logic
        toolDraw.setOnClickListener {
            if (currentActiveTool == toolDraw) {
                toolOptionsLayout.visibility = View.GONE
                currentActiveTool?.isSelected = false
                currentActiveTool = null
            } else {
                toolOptionsLayout.visibility = View.VISIBLE
                drawOptionsLayout.visibility = View.VISIBLE
                cropOptionsLayout.visibility = View.GONE
                adjustOptionsLayout.visibility = View.GONE
                savePanel.visibility = View.GONE // Hide save panel
                currentActiveTool?.isSelected = false
                toolDraw.isSelected = true
                currentActiveTool = toolDraw
            }
        }

        toolCrop.setOnClickListener {
            if (currentActiveTool == toolCrop) {
                toolOptionsLayout.visibility = View.GONE
                currentActiveTool?.isSelected = false
                currentActiveTool = null
            } else {
                toolOptionsLayout.visibility = View.VISIBLE
                cropOptionsLayout.visibility = View.VISIBLE
                drawOptionsLayout.visibility = View.GONE
                adjustOptionsLayout.visibility = View.GONE
                savePanel.visibility = View.GONE // Hide save panel
                currentActiveTool?.isSelected = false
                toolCrop.isSelected = true
                currentActiveTool = toolCrop
            }
        }

        toolAdjust.setOnClickListener {
            if (currentActiveTool == toolAdjust) {
                toolOptionsLayout.visibility = View.GONE
                currentActiveTool?.isSelected = false
                currentActiveTool = null
            } else {
                toolOptionsLayout.visibility = View.VISIBLE
                adjustOptionsLayout.visibility = View.VISIBLE
                drawOptionsLayout.visibility = View.GONE
                cropOptionsLayout.visibility = View.GONE
                savePanel.visibility = View.GONE // Hide save panel
                currentActiveTool?.isSelected = false
                toolAdjust.isSelected = true
                currentActiveTool = toolAdjust
            }
        }

        // Initialize Save Panel buttons
        val buttonSaveCopy: Button = findViewById(R.id.button_save_copy)
        val buttonOverwrite: Button = findViewById(R.id.button_overwrite)

        val touchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1.0f
                }
            }
            false
        }

        buttonSaveCopy.setOnTouchListener(touchListener)
        buttonOverwrite.setOnTouchListener(touchListener)
        findViewById<RadioButton>(R.id.radio_jpg)
        findViewById<RadioButton>(R.id.radio_png)
        findViewById<RadioButton>(R.id.radio_webp)

        // Initialize Draw Options
        findViewById<SeekBar>(R.id.draw_size_slider)
        findViewById<SeekBar>(R.id.draw_opacity_slider)
        val drawModePen: ImageView = findViewById(R.id.draw_mode_pen)
        val drawModeCircle: ImageView = findViewById(R.id.draw_mode_circle)
        val drawModeSquare: ImageView = findViewById(R.id.draw_mode_square)

        drawModePen.setOnClickListener {
            currentDrawMode?.isSelected = false
            drawModePen.isSelected = true
            currentDrawMode = drawModePen
        }
        drawModeCircle.setOnClickListener {
            currentDrawMode?.isSelected = false
            drawModeCircle.isSelected = true
            currentDrawMode = drawModeCircle
        }
        drawModeSquare.setOnClickListener {
            currentDrawMode?.isSelected = false
            drawModeSquare.isSelected = true
            currentDrawMode = drawModeSquare
        }

        // Initialize Crop Options
        val cropModeFreeform: ImageView = findViewById(R.id.crop_mode_freeform)
        val cropModeSquare: ImageView = findViewById(R.id.crop_mode_square)
        val cropModePortrait: ImageView = findViewById(R.id.crop_mode_portrait)
        val cropModeLandscape: ImageView = findViewById(R.id.crop_mode_landscape)

        cropModeFreeform.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModeFreeform.isSelected = true
            currentCropMode = cropModeFreeform
        }
        cropModeSquare.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModeSquare.isSelected = true
            currentCropMode = cropModeSquare
        }
        cropModePortrait.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModePortrait.isSelected = true
            currentCropMode = cropModePortrait
        }
        cropModeLandscape.setOnClickListener {
            currentCropMode?.isSelected = false
            cropModeLandscape.isSelected = true
            currentCropMode = cropModeLandscape
        }

        // Initialize Adjust Options (no logic yet)
        findViewById<SeekBar>(R.id.adjust_brightness_slider)
        findViewById<SeekBar>(R.id.adjust_contrast_slider)
        findViewById<SeekBar>(R.id.adjust_saturation_slider)

        // Color Swatches Logic
        val colorBlack: View = findViewById(R.id.color_black)
        val colorWhite: View = findViewById(R.id.color_white)
        val colorRed: View = findViewById(R.id.color_red)
        val colorGreen: View = findViewById(R.id.color_green)
        val colorBlue: View = findViewById(R.id.color_blue)
        val colorYellow: View = findViewById(R.id.color_yellow)
        val colorOrange: View = findViewById(R.id.color_orange)
        val colorPink: View = findViewById(R.id.color_pink)

        val colorClickListener = View.OnClickListener { v ->
            currentColor?.isSelected = false
            v.isSelected = true
            currentColor = v
        }

        colorBlack.setOnClickListener(colorClickListener)
        colorWhite.setOnClickListener(colorClickListener)
        colorRed.setOnClickListener(colorClickListener)
        colorGreen.setOnClickListener(colorClickListener)
        colorBlue.setOnClickListener(colorClickListener)
        colorYellow.setOnClickListener(colorClickListener)
        colorOrange.setOnClickListener(colorClickListener)
        colorPink.setOnClickListener(colorClickListener)
    }
}
