# UI Hierarchy Reference
*Complete ID hierarchy for Edit SS Android App - All view IDs and references for AI agent interactions*

## Root Layout
```
FrameLayout (root_layout)
└── ConstraintLayout (main_content_layout)
    ├── View (ad_banner) - Google Ad Banner placeholder
    ├── LinearLayout (action_bar)
    │   ├── ImageView (button_import) - @drawable/importicon
    │   ├── ImageView (button_camera) - @drawable/cameraicon  
    │   ├── ImageView (button_undo) - @drawable/undoicon
    │   ├── ImageView (button_redo) - @drawable/redoicon
    │   ├── ImageView (button_share) - @drawable/shareicon
    │   └── ImageView (button_save) - @drawable/saveicon
    ├── ImageView (canvas) - Main editing canvas
    ├── LinearLayout (tool_options)
    │   ├── LinearLayout (draw_options) - @android:id/visibility gone
    │   │   ├── LinearLayout - Draw mode row
    │   │   │   ├── ImageView (draw_mode_pen) - @drawable/penicon
    │   │   │   ├── ImageView (draw_mode_circle) - @drawable/circleicon
    │   │   │   └── ImageView (draw_mode_square) - @drawable/squareicon
    │   │   ├── LinearLayout - Color palette row
    │   │   │   ├── FrameLayout (color_red_container)
    │   │   │   │   ├── View - Background @drawable/round_color_background
    │   │   │   │   │   └── android:backgroundTint="#FFFF0000"
    │   │   │   │   └── ImageView - Border @drawable/faded_round_border
    │   │   │   │       └── android:tag="border"
    │   │   │   ├── FrameLayout (color_green_container)
    │   │   │   │   ├── View - @drawable/round_color_background + #FF00FF00
    │   │   │   │   └── ImageView - @drawable/faded_round_border (tag="border")
    │   │   │   ├── FrameLayout (color_blue_container)
    │   │   │   │   ├── View - @drawable/round_color_background + #FF0000FF
    │   │   │   │   └── ImageView - @drawable/faded_round_border (tag="border")
    │   │   │   ├── FrameLayout (color_yellow_container)
    │   │   │   │   ├── View - @drawable/round_color_background + #FFFFFF00
    │   │   │   │   └── ImageView - @drawable/faded_round_border (tag="border")
    │   │   │   ├── FrameLayout (color_orange_container)
    │   │   │   │   ├── View - @drawable/round_color_background + #FFFFA500
    │   │   │   │   └── ImageView - @drawable/faded_round_border (tag="border")
    │   │   │   ├── FrameLayout (color_pink_container)
    │   │   │   │   ├── View - @drawable/round_color_background + #FFFFC0CB
    │   │   │   │   └── ImageView - @drawable/faded_round_border (tag="border")
    │   │   │   ├── FrameLayout (color_black_container)
    │   │   │   │   ├── View - @drawable/round_color_background + #FF000000
    │   │   │   │   └── ImageView - @drawable/faded_round_border (tag="border")
    │   │   │   └── FrameLayout (color_white_container)
    │   │   │       ├── View - @drawable/round_color_background + #FFFFFFFF
    │   │   │       └── ImageView - @drawable/faded_round_border (tag="border")
    │   │   ├── TextView - "Size" label
    │   │   ├── SeekBar (draw_size_slider) - Draw tool size control
    │   │   ├── TextView - "Opacity" label
    │   │   └── SeekBar (draw_opacity_slider) - Draw tool opacity control
    │   ├── LinearLayout (crop_options) - @android:id/visibility gone
    │   │   ├── ImageView (crop_mode_freeform) - @drawable/cropfreeformicon
    │   │   ├── ImageView (crop_mode_square) - @drawable/cropsquareicon
    │   │   ├── ImageView (crop_mode_portrait) - @drawable/cropportraiticon
    │   │   └── ImageView (crop_mode_landscape) - @drawable/croplandscapeicon
    │   └── LinearLayout (adjust_options) - @android:id/visibility gone
    │       ├── TextView - "Brightness" label
    │       ├── SeekBar (adjust_brightness_slider) - Brightness control
    │       ├── TextView - "Contrast" label
    │       ├── SeekBar (adjust_contrast_slider) - Contrast control
    │       ├── TextView - "Saturation" label
    │       └── SeekBar (adjust_saturation_slider) - Saturation control
    └── LinearLayout (tools)
        ├── ImageView (tool_draw) - @drawable/drawicon
        ├── ImageView (tool_crop) - @drawable/cropicon
        └── ImageView (tool_adjust) - @drawable/adjusticon
```

## Modal Overlays
```
FrameLayout (root_layout) [continued]
├── View (scrim) - Modal overlay backdrop
└── ConstraintLayout (save_panel) - Save dialog
    ├── LinearLayout (save_actions)
    │   ├── Button (button_save_copy) - "Save Copy" 
    │   │   └── android:backgroundTint="?attr/colorSecondary"
    │   └── Button (button_overwrite) - "Overwrite"
    │       └── android:backgroundTint="?attr/colorSecondary"
    └── RadioGroup (save_file_types)
        ├── RadioButton (radio_jpg) - "JPG"
        ├── RadioButton (radio_png) - "PNG"
        └── RadioButton (radio_webp) - "WEBP"
```

## Key Interaction Patterns

### Tool Selection
- `tool_draw` → shows `draw_options` (hides others)
- `tool_crop` → shows `crop_options` (hides others)  
- `tool_adjust` → shows `adjust_options` (hides others)

### Color Selection
- Click any `color_*_container` → toggle visibility of child ImageView with `tag="border"`
- Selected color shows `@drawable/faded_round_border` overlay

### Draw Tool Modes
- `draw_mode_pen` → Pen drawing mode
- `draw_mode_circle` → Circle drawing mode
- `draw_mode_square` → Square drawing mode

### Crop Tool Modes  
- `crop_mode_freeform` → Freeform cropping
- `crop_mode_square` → Square cropping
- `crop_mode_portrait` → Portrait aspect ratio
- `crop_mode_landscape` → Landscape aspect ratio

### Action Bar Actions
- `button_import` → Import image from gallery
- `button_camera` → Capture image from camera
- `button_undo` → Undo last action
- `button_redo` → Redo last action
- `button_share` → Share current image
- `button_save` → Show save panel modal

### Save Panel Flow
- `button_save` → Show `save_panel` + `scrim`
- `button_save_copy` → Save as new file
- `button_overwrite` → Overwrite original file
- Format selection via `radio_jpg`, `radio_png`, `radio_webp`

## All View IDs for Reference
```
ad_banner, main_content_layout, action_bar, button_import, button_camera, 
button_undo, button_redo, button_share, button_save, canvas, tool_options, 
draw_options, draw_mode_pen, draw_mode_circle, draw_mode_square, 
color_red_container, color_green_container, color_blue_container, 
color_yellow_container, color_orange_container, color_pink_container, 
color_black_container, color_white_container, draw_size_slider, 
draw_opacity_slider, crop_options, crop_mode_freeform, crop_mode_square, 
crop_mode_portrait, crop_mode_landscape, adjust_options, 
adjust_brightness_slider, adjust_contrast_slider, adjust_saturation_slider, 
tools, tool_draw, tool_crop, tool_adjust, scrim, save_panel, save_actions, 
button_save_copy, button_overwrite, save_file_types, radio_jpg, radio_png, 
radio_webp