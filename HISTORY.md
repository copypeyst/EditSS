## UI Implementation & Refinement (Phase 1)

*   **Layout:** `activity_main.xml` structure (Google Ad Banner, ActionBar, Canvas, Tools, ToolOptions, Save Panel) + responsiveness.
*   **Icons:** All ActionBar icons integrated with vector drawables and ripple effects.
*   **Save Panel:** Centered modal with scrim overlay, show/hide functionality, rounded corners. Buttons: "Save Copy", "Overwrite" using theme colors (`colorSecondary`/`colorOnSecondary`). File types: JPG, PNG, WEBP with RadioButton selection.
*   **ToolOptions:** Contextual show/hide functionality, properly structured for each tool.
    *   **Draw:** Modes (Pen/Circle/Square) with button backgrounds. Colors: 8-color palette with circular ring selection indicators using secondary theme color. Sliders (Size/Opacity): Properly labeled and positioned.
    *   **Crop:** Four crop modes (FreeForm, Square, Portrait, Landscape) with mode-specific icons.
    *   **Adjust:** Text labels + Brightness/Contrast/Saturation sliders.
*   **Color Selection:** Implemented circular ring indicators using `faded_round_border.xml` with secondary theme color (#FF03DAC5) for selected state visual feedback.
*   **Refinements:** Margins, padding, sizes optimized for dark theme consistency.
*   **Dependencies:** `androidx.constraintlayout` correctly set to version 2.1.4 in `libs.versions.toml`.
