package com.tamad.editss

import android.graphics.*
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Complete image state that represents all operations applied to the image
 */
data class ImageState(
    val baseBitmap: Bitmap,
    val drawingPaths: List<DrawingAction> = emptyList(),
    val cropRect: RectF? = null,
    val adjustments: AdjustState = AdjustState(),
    val timestamp: Long = System.currentTimeMillis()
) {
    fun copyWithChanges(
        baseBitmap: Bitmap? = null,
        drawingPaths: List<DrawingAction>? = null,
        cropRect: RectF? = null,
        adjustments: AdjustState? = null
    ): ImageState {
        return ImageState(
            baseBitmap = baseBitmap ?: this.baseBitmap,
            drawingPaths = drawingPaths ?: this.drawingPaths,
            cropRect = cropRect ?: this.cropRect,
            adjustments = adjustments ?: this.adjustments,
            timestamp = System.currentTimeMillis()
        )
    }
}

/**
 * Types of operations that can be performed
 */
enum class OperationType {
    DRAW_STROKE,
    CROP_APPLY,
    CROP_CANCEL,
    ADJUST_APPLY,
    ADJUST_RESET,
    IMAGE_LOAD,
    CLEAR_ALL
}

/**
 * Atomic edit action that preserves complete state and knows how to reverse itself
 */
data class AtomicEditAction(
    val timestamp: Long,
    val operationType: OperationType,
    val previousState: ImageState,
    val nextState: ImageState,
    val inverseOperation: () -> Unit
) {
    companion object {
        fun createDrawingAction(
            previousPaths: List<DrawingAction>,
            newPaths: List<DrawingAction>,
            baseBitmap: Bitmap,
            adjustments: AdjustState
        ): AtomicEditAction {
            val previousState = ImageState(baseBitmap, previousPaths, null, adjustments)
            val nextState = ImageState(baseBitmap, newPaths, null, adjustments)
            
            val inverseOperation = {
                // Restore previous drawing state
                // This will be implemented in the CanvasView
            }
            
            return AtomicEditAction(
                timestamp = System.currentTimeMillis(),
                operationType = OperationType.DRAW_STROKE,
                previousState = previousState,
                nextState = nextState,
                inverseOperation = inverseOperation
            )
        }
        
        fun createCropAction(
            previousBitmap: Bitmap,
            croppedBitmap: Bitmap,
            cropRect: RectF,
            cropMode: CropMode
        ): AtomicEditAction {
            val previousState = ImageState(previousBitmap)
            val nextState = ImageState(croppedBitmap)
            
            val inverseOperation = {
                // Restore previous bitmap and clear crop state
            }
            
            return AtomicEditAction(
                timestamp = System.currentTimeMillis(),
                operationType = OperationType.CROP_APPLY,
                previousState = previousState,
                nextState = nextState,
                inverseOperation = inverseOperation
            )
        }
        
        fun createAdjustAction(
            previousBitmap: Bitmap,
            adjustedBitmap: Bitmap,
            adjustments: AdjustState
        ): AtomicEditAction {
            val previousState = ImageState(previousBitmap)
            val nextState = ImageState(adjustedBitmap, adjustments = adjustments)
            
            val inverseOperation = {
                // Restore previous adjustments
            }
            
            return AtomicEditAction(
                timestamp = System.currentTimeMillis(),
                operationType = OperationType.ADJUST_APPLY,
                previousState = previousState,
                nextState = nextState,
                inverseOperation = inverseOperation
            )
        }
    }
}

/**
 * Memory-efficient history manager that prevents action merging
 */
class AtomicHistoryManager {
    private val history = mutableListOf<AtomicEditAction>()
    private var currentIndex = -1
    private val maxHistorySize = 20 // Reduced from 50 bitmaps to 20 atomic actions
    
    fun addAction(action: AtomicEditAction) {
        // Remove any history after current position (when adding new action after undo)
        if (currentIndex < history.size - 1) {
            history.subList(currentIndex + 1, history.size).clear()
        }
        
        history.add(action)
        currentIndex = history.size - 1
        
        // Limit history size
        if (history.size > maxHistorySize) {
            history.removeFirst()
            currentIndex = history.size - 1
        }
    }
    
    fun canUndo(): Boolean = currentIndex > 0
    fun canRedo(): Boolean = currentIndex < history.size - 1
    
    fun undo(): AtomicEditAction? {
        return if (canUndo()) {
            currentIndex--
            history[currentIndex]
        } else null
    }
    
    fun redo(): AtomicEditAction? {
        return if (canRedo()) {
            currentIndex++
            history[currentIndex]
        } else null
    }
    
    fun clear() {
        history.clear()
        currentIndex = -1
    }
    
    fun getCurrentState(): ImageState? {
        return if (currentIndex >= 0 && currentIndex < history.size) {
            history[currentIndex].nextState
        } else null
    }
    
    fun getInitialState(): ImageState? {
        return if (history.isNotEmpty()) {
            history.first().previousState
        } else null
    }
}