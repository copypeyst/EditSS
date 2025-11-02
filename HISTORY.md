## UI Implementation & Refinement (Phase 1)

*   **Layout:** `activity_main.xml` structure (ActionBar, Canvas, Tools, ToolOptions, Save Panel) + responsiveness.
*   **Icons:** Integrated & positioned.
*   **Save Panel:** Centered, show/hide, tap-outside-close, rounded edges. Buttons: "Save Copy", "Overwrite" (color: `teal_200`). File types: JPG, PNG, WEBP.
*   **ToolOptions:** Contextual show/hide, reselect-to-close.
    *   **Draw:** Modes (Pen/Circle/Square) outlined & spaced. Colors: Round, spaced, fit screen. Sliders (Size/Opacity): Padded.
    *   **Adjust:** Text + sliders.
*   **Refinements:** Margins, padding, sizes optimized.
*   **Fixes:** `androidx.constraintlayout` updated to `2.1.4`.
