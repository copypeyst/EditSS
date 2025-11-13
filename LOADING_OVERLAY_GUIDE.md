# Loading Overlay System Implementation Guide

## Overview
This guide explains the implementation of the loading overlay system in EditSS that appears on top of all UI elements, including save panels, tool options, and action buttons.

## Problem Solved
Previously, loading spinners only appeared in front of the canvas, but could be covered by:
- Save panel (pop-up modal)
- Tool options panels
- Action buttons and other UI elements

## Solution Implemented

### 1. Top-Level Overlay Container
Added a top-level `FrameLayout` with ID `overlay_container` that sits at the root level of the layout hierarchy, above all other UI elements.

```xml
<!-- Top-level overlay container for loading spinner and other overlays -->
<FrameLayout
    android:id="@+id/overlay_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:visibility="gone"
    android:clickable="true"
    android:focusable="true"
    android:background="#80000000">

    <!-- Loading Spinner -->
    <LinearLayout
        android:id="@+id/loading_spinner_layout"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:orientation="vertical"
        android:padding="24dp"
        android:background="@drawable/loading_dialog_background"
        android:clickable="true"
        android:focusable="true">

        <ProgressBar
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:indeterminateTint="@android:color/white" />

        <TextView
            android:id="@+id/loading_text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="Loading..."
            android:textColor="@android:color/white"
            android:textSize="16sp"
            android:gravity="center" />

    </LinearLayout>

</FrameLayout>
```

### 2. Kotlin Implementation

#### Loading State Variables
```kotlin
// Loading overlay elements
private lateinit var overlayContainer: FrameLayout
private lateinit var loadingSpinnerLayout: LinearLayout
private lateinit var loadingText: TextView
```

#### Core Loading Functions
```kotlin
private fun showLoadingSpinner(message: String = "Loading...") {
    loadingText.text = message
    overlayContainer.visibility = View.VISIBLE
    
    // Disable all interactive elements while loading
    disableAllInteractiveElements(true)
}

private fun hideLoadingSpinner() {
    overlayContainer.visibility = View.GONE
    
    // Re-enable all interactive elements
    disableAllInteractiveElements(false)
}

private fun disableAllInteractiveElements(disabled: Boolean) {
    // Disable main action buttons
    findViewById<ImageView>(R.id.button_import)?.isEnabled = !disabled
    findViewById<ImageView>(R.id.button_camera)?.isEnabled = !disabled
    findViewById<ImageView>(R.id.button_undo)?.isEnabled = !disabled
    findViewById<ImageView>(R.id.button_redo)?.isEnabled = !disabled
    findViewById<ImageView>(R.id.button_share)?.isEnabled = !disabled
    findViewById<ImageView>(R.id.button_save)?.isEnabled = !disabled
    
    // Disable tool buttons
    findViewById<ImageView>(R.id.tool_draw)?.isEnabled = !disabled
    findViewById<ImageView>(R.id.tool_crop)?.isEnabled = !disabled
    findViewById<ImageView>(R.id.tool_adjust)?.isEnabled = !disabled
    
    // Disable save panel if visible
    if (savePanel.visibility == View.VISIBLE) {
        savePanel.isEnabled = !disabled
    }
    
    // Disable tool options if visible
    if (toolOptionsLayout.visibility == View.VISIBLE) {
        toolOptionsLayout.isEnabled = !disabled
    }
}
```

### 3. Integration Points

#### Image Loading
The `loadImageFromUri()` function now shows the loading spinner during image loading:
```kotlin
// Show loading overlay
showLoadingSpinner("Loading image...")

// ... after successful load or failure
hideLoadingSpinner()
```

#### Save Operations
Both `saveImageAsCopy()` and `overwriteCurrentImage()` functions now show loading spinners:
```kotlin
// Show loading overlay to prevent spamming
showLoadingSpinner("Saving image...")
// ... or "Overwriting image..."
```

#### Interactive Element Disabling
When the loading overlay is active:
- All action buttons are disabled
- All tool buttons are disabled
- Save panel interactions are disabled
- Tool options interactions are disabled

### 4. Key Features

1. **Top-Level Z-Order**: The overlay container is positioned at the root level, ensuring it appears above all other UI elements.

2. **Full-Screen Coverage**: The overlay covers the entire screen with a semi-transparent background, clearly indicating the app is busy.

3. **Interactive Element Disabling**: All interactive UI elements are disabled while loading, preventing user actions that could interfere with ongoing operations.

4. **Customizable Messages**: The loading spinner can display different messages based on the operation:
   - "Loading image..." for image loading
   - "Saving image..." for save operations
   - "Overwriting image..." for overwrite operations

5. **Background Click Prevention**: The overlay is clickable and focusable, preventing accidental touches on underlying UI elements.

6. **Consistent Styling**: Uses the existing `loading_dialog_background.xml` drawable for consistent visual styling.

### 5. Benefits

- **Better UX**: Users clearly see when the app is processing operations
- **Prevents Spamming**: Users cannot spam buttons during long-running operations
- **Consistent Experience**: Loading states work consistently across all operations
- **Above All UI**: No UI element can cover the loading spinner
- **Thread Safe**: Properly handles UI updates on the main thread

### 6. Usage Examples

To add a loading spinner to any new operation:

```kotlin
// Show loading with custom message
showLoadingSpinner("Processing...")

try {
    // Perform operation in background
    withContext(Dispatchers.IO) {
        // Your long-running operation here
    }.also { result ->
        hideLoadingSpinner()
        // Handle result
    }
} catch (e: Exception) {
    hideLoadingSpinner()
    // Handle error
}
```

This implementation ensures that the loading spinner always appears on top of all UI elements, providing users with clear visual feedback during long-running operations.