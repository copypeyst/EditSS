package com.tamad.editss

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private lateinit var savePanel: View
    private lateinit var toolOptionsLayout: LinearLayout
    private lateinit var drawOptionsLayout: LinearLayout
    private lateinit var cropOptionsLayout: LinearLayout
    private lateinit var adjustOptionsLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find UI elements
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
        }

        // Tool Buttons Logic
        toolDraw.setOnClickListener {
            toolOptionsLayout.visibility = View.VISIBLE
            drawOptionsLayout.visibility = View.VISIBLE
            cropOptionsLayout.visibility = View.GONE
            adjustOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
        }

        toolCrop.setOnClickListener {
            toolOptionsLayout.visibility = View.VISIBLE
            cropOptionsLayout.visibility = View.VISIBLE
            drawOptionsLayout.visibility = View.GONE
            adjustOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
        }

        toolAdjust.setOnClickListener {
            toolOptionsLayout.visibility = View.VISIBLE
            adjustOptionsLayout.visibility = View.VISIBLE
            drawOptionsLayout.visibility = View.GONE
            cropOptionsLayout.visibility = View.GONE
            savePanel.visibility = View.GONE // Hide save panel
        }

        // Initialize Save Panel buttons (no logic yet)
        findViewById<Button>(R.id.button_save_copy)
        findViewById<Button>(R.id.button_overwrite)
        findViewById<RadioButton>(R.id.radio_jpg)
        findViewById<RadioButton>(R.id.radio_png)
        findViewById<RadioButton>(R.id.radio_webp)

        // Initialize Draw Options (no logic yet)
        findViewById<SeekBar>(R.id.draw_size_slider)
        findViewById<SeekBar>(R.id.draw_opacity_slider)
        findViewById<ImageView>(R.id.draw_mode_pen)
        findViewById<ImageView>(R.id.draw_mode_circle)
        findViewById<ImageView>(R.id.draw_mode_square)

        // Initialize Crop Options (no logic yet)
        findViewById<ImageView>(R.id.crop_mode_freeform)
        findViewById<ImageView>(R.id.crop_mode_square)
        findViewById<ImageView>(R.id.crop_mode_portrait)
        findViewById<ImageView>(R.id.crop_mode_landscape)

        // Initialize Adjust Options (no logic yet)
        findViewById<SeekBar>(R.id.adjust_brightness_slider)
        findViewById<SeekBar>(R.id.adjust_contrast_slider)
        findViewById<SeekBar>(R.id.adjust_saturation_slider)
    }
}
