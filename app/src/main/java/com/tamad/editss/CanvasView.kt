package com.tamad.editss

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class CanvasView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paint = Paint()
    private val imagePaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }
    private val overlayPaint = Paint().apply { color = Color.BLACK; alpha = 128 }
    private val overlayPath = Path()
    private val cropPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f }
    private val cropCornerPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = Color.WHITE; alpha = 192 }
    private val checkerDrawable = CheckerDrawable()

    private var baseBitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val imageBounds = RectF()
    private var scaleFactor = 1.0f
    private var translationX = 0f
    private var translationY = 0f

    private var lastPointerCount = 1
    private var isZooming = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private var currentTool: ToolType = ToolType.DRAW
    private var currentCropMode: com.tamad.editss.CropMode = com.tamad.editss.CropMode.FREEFORM
    private var isCropModeActive = false
    private var cropRect = RectF()
    private var isCropping = false
    private var isMovingCropRect = false
    private var isResizingCropRect = false
    private var resizeHandle = 0
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    private var cropStartX = 0f
    private var cropStartY = 0f
    private var cropStartLeft = 0f
    private var cropStartTop = 0f
    private var cropStartRight = 0f
    private var cropStartBottom = 0f

    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f

    private var density = 1f
    private var isSketchMode = false

    var onCropApplied: ((Bitmap) -> Unit)? = null
    var onCropCanceled: (() -> Unit)? = null
    var onUndoAction: (() -> Unit)? = null
    var onRedoAction: (() -> Unit)? = null
    var onBitmapChanged: ((EditAction.BitmapChange) -> Unit)? = null

    private val saveDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val saveScope = CoroutineScope(saveDispatcher + SupervisorJob())

    private val snapshots = TreeMap<Int, String>()
    private val actions = ArrayList<EditAction>()
    private var actionIndex = -1
    private var savedActionIndex = -1
    private val snapshotInterval = 6
    private val maxSnapshots = 10

    private var currentStrokePath: Path? = null
    private var currentStrokePaint: Paint? = null
    private var currentStrokePoints: MutableList<PointF>? = null

    init {
        density = context.resources.displayMetrics.density
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
    }

    sealed class EditAction {
        data class Draw(val path: Path, val paint: Paint) : EditAction()
        data class Crop(val rect: RectF) : EditAction()
        data class Adjust(val brightness: Float, val contrast: Float, val saturation: Float) : EditAction()
        data class BitmapChange(val path: String) : EditAction()
    }

    enum class ToolType { DRAW, CROP, ADJUST }

    fun markAsSaved() {
        savedActionIndex = actionIndex
    }

    fun hasUnsavedChanges(): Boolean = savedActionIndex != actionIndex

    private fun addAction(action: EditAction) {
        if (actionIndex < actions.size - 1) {
            val toDelete = actions.subList(actionIndex + 1, actions.size)
            toDelete.clear()
        }
        actions.add(action)
        actionIndex = actions.size - 1
        maybeCreateSnapshot()
        cleanupOldSnapshotsIfNeeded()
    }

    private fun maybeCreateSnapshot() {
        val lastSnapIdx = if (snapshots.isEmpty()) -1 else snapshots.lastKey()
        val distance = actionIndex - lastSnapIdx
        if (distance >= snapshotInterval || snapshots.isEmpty()) {
            createSnapshotAsync()
        }
    }

    private fun createSnapshotAsync() {
        val bmp = buildBitmapForIndex(actionIndex) ?: return
        saveScope.launch {
            try {
                val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
                val ext = if (compressFormat == Bitmap.CompressFormat.PNG) "png" else "webp"
                val file = File(context.cacheDir, "snap_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext")
                FileOutputStream(file).use { out ->
                    bmp.compress(compressFormat, 100, out)
                }
                snapshots[actionIndex] = file.absolutePath
                withContext(Dispatchers.Main) { onBitmapChanged?.invoke(EditAction.BitmapChange(file.absolutePath)) }
                maintainSnapshotLimit()
            } catch (_: Exception) {}
        }
    }

    private fun maintainSnapshotLimit() {
        while (snapshots.size > maxSnapshots) {
            val first = snapshots.firstKey()
            val path = snapshots.remove(first)
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    private fun cleanupOldSnapshotsIfNeeded() {
        val keysToRemove = ArrayList<Int>()
        val keepRangeStart = max(0, actionIndex - snapshotInterval * (maxSnapshots))
        for (k in snapshots.keys) {
            if (k < keepRangeStart) keysToRemove.add(k)
        }
        keysToRemove.forEach { key ->
            val path = snapshots.remove(key)
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    fun clearHistoryCache() {
        actions.clear()
        actionIndex = -1
        savedActionIndex = -1
        val pathsToDelete = ArrayList(snapshots.values)
        snapshots.clear()
        saveScope.launch { withContext(NonCancellable) { pathsToDelete.forEach { try { File(it).delete() } catch (_: Exception) {} } } }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        saveScope.cancel()
        saveDispatcher.close()
    }

    fun setBitmap(bitmap: Bitmap?) {
        clearHistoryCache()
        baseBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
        if (baseBitmap != null) {
            createSnapshotAsync()
        }
        savedActionIndex = 0
        background = ContextCompat.getDrawable(context, R.drawable.outer_bounds)
        updateImageMatrix()
        invalidate()
        post { if (currentTool == ToolType.CROP && isCropModeActive) initializeDefaultCropRect() }
    }

    fun setToolType(toolType: ToolType) {
        currentTool = toolType
        if (toolType == ToolType.CROP) {
            if (isCropModeActive) initializeDefaultCropRect()
        } else {
            isCropModeActive = false
        }
        invalidate()
    }

    fun setCropMode(cropMode: com.tamad.editss.CropMode) {
        currentCropMode = cropMode
        isCropModeActive = true
        cropRect.setEmpty()
        if (currentTool == ToolType.CROP) initializeDefaultCropRect()
        invalidate()
    }

    fun setCropModeInactive() {
        isCropModeActive = false
        if (currentTool == ToolType.CROP) cropRect.setEmpty()
        invalidate()
    }

    fun setAdjustments(bright: Float, contr: Float, sat: Float) {
        brightness = bright
        contrast = contr
        saturation = sat
        updateColorFilter()
        invalidate()
    }

    fun resetAdjustments() {
        setAdjustments(0f, 1f, 1f)
    }

    private fun updateColorFilter() {
        val cm = ColorMatrix()
        val translation = brightness + (1f - contrast) * 128f
        cm.set(floatArrayOf(
            contrast, 0f, 0f, 0f, translation,
            0f, contrast, 0f, 0f, translation,
            0f, 0f, contrast, 0f, translation,
            0f, 0f, 0f, 1f, 0f
        ))
        val sm = ColorMatrix().apply { setSaturation(saturation) }
        cm.postConcat(sm)
        imagePaint.colorFilter = ColorMatrixColorFilter(cm)
        imagePaint.xfermode = null
    }

    fun applyAdjustmentsToBitmap(): Bitmap? {
        val src = baseBitmap ?: return null
        val adjustedBitmap = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(adjustedBitmap)
        val p = Paint().apply { colorFilter = imagePaint.colorFilter }
        c.drawBitmap(src, 0f, 0f, p)
        baseBitmap = adjustedBitmap
        addAction(EditAction.Adjust(brightness, contrast, saturation))
        invalidate()
        return baseBitmap
    }

    fun applyCrop(): Bitmap? {
        val src = baseBitmap ?: return null
        if (cropRect.isEmpty) return null
        val inverse = Matrix()
        imageMatrix.invert(inverse)
        val imgRect = RectF()
        inverse.mapRect(imgRect, cropRect)
        val left = imgRect.left.coerceIn(0f, src.width.toFloat())
        val top = imgRect.top.coerceIn(0f, src.height.toFloat())
        val right = imgRect.right.coerceIn(0f, src.width.toFloat())
        val bottom = imgRect.bottom.coerceIn(0f, src.height.toFloat())
        if (right <= left || bottom <= top) return null
        try {
            val cropped = Bitmap.createBitmap(src, left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
            baseBitmap = cropped.copy(Bitmap.Config.ARGB_8888, true)
            addAction(EditAction.Crop(RectF(0f, 0f, baseBitmap!!.width.toFloat(), baseBitmap!!.height.toFloat())))
            cropRect.setEmpty()
            scaleFactor = 1f
            translationX = 0f
            translationY = 0f
            updateImageMatrix()
            invalidate()
            onCropApplied?.invoke(baseBitmap!!)
            return baseBitmap
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    fun cancelCrop() {
        cropRect.setEmpty()
        invalidate()
        onCropCanceled?.invoke()
    }

    fun undo(): Bitmap? {
        if (actionIndex >= 0) {
            actionIndex--
            rebuildToIndex(actionIndex)
            onUndoAction?.invoke()
            return baseBitmap
        }
        return null
    }

    fun redo(): Bitmap? {
        if (actionIndex < actions.size - 1) {
            actionIndex++
            rebuildToIndex(actionIndex)
            onRedoAction?.invoke()
            return baseBitmap
        }
        return null
    }

    fun canUndo(): Boolean = actionIndex >= 0
    fun canRedo(): Boolean = actionIndex < actions.size - 1

    private fun rebuildToIndex(index: Int) {
        var working: Bitmap? = null
        var start = 0
        val snapEntry = snapshots.floorEntry(index)
        if (snapEntry != null) {
            val snapPath = snapEntry.value
            val f = File(snapPath)
            if (f.exists()) {
                val opts = BitmapFactory.Options().apply { inMutable = true }
                val sBmp = BitmapFactory.decodeFile(snapPath, opts)
                if (sBmp != null) {
                    working = sBmp.copy(Bitmap.Config.ARGB_8888, true)
                    start = snapEntry.key + 1
                }
            }
        }
        if (working == null) {
            val base = baseBitmap ?: return
            working = base.copy(Bitmap.Config.ARGB_8888, true)
            start = 0
        }
        val canvas = Canvas(working)
        val inv = Matrix()
        imageMatrix.invert(inv)
        canvas.concat(inv)
        val end = min(index, actions.size - 1)
        for (i in start..end) {
            val act = actions[i]
            when (act) {
                is EditAction.Draw -> canvas.drawPath(act.path, act.paint)
                is EditAction.Adjust -> {
                    val bmp = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    val p = Paint().apply { colorFilter = ColorMatrixColorFilter(generateColorMatrix(act.brightness, act.contrast, act.saturation)) }
                    c.drawBitmap(working, 0f, 0f, p)
                    working.recycle()
                    working = bmp
                    canvas.setBitmap(working)
                    canvas.concat(inv)
                }
                is EditAction.Crop -> {
                    val r = act.rect
                    val left = r.left.toInt().coerceIn(0, working.width)
                    val top = r.top.toInt().coerceIn(0, working.height)
                    val w = r.width().toInt().coerceIn(0, working.width - left)
                    val h = r.height().toInt().coerceIn(0, working.height - top)
                    if (w > 0 && h > 0) {
                        val cropped = Bitmap.createBitmap(working, left, top, w, h)
                        working.recycle()
                        working = cropped
                        canvas.setBitmap(working)
                        canvas.concat(inv)
                    }
                }
                else -> {}
            }
        }
        baseBitmap?.recycle()
        baseBitmap = working
        updateImageMatrix()
        invalidate()
    }

    private fun generateColorMatrix(bright: Float, contr: Float, sat: Float): ColorMatrix {
        val cm = ColorMatrix()
        val translation = bright + (1f - contr) * 128f
        cm.set(floatArrayOf(
            contr, 0f, 0f, 0f, translation,
            0f, contr, 0f, 0f, translation,
            0f, 0f, contr, 0f, translation,
            0f, 0f, 0f, 1f, 0f
        ))
        val sm = ColorMatrix().apply { setSaturation(sat) }
        cm.postConcat(sm)
        return cm
    }

    private fun buildBitmapForIndex(index: Int): Bitmap? {
        val base = baseBitmap ?: return null
        val mutable = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val inv = Matrix()
        imageMatrix.invert(inv)
        canvas.concat(inv)
        val end = min(index, actions.size - 1)
        for (i in 0..end) {
            val act = actions[i]
            when (act) {
                is EditAction.Draw -> canvas.drawPath(act.path, act.paint)
                is EditAction.Adjust -> {
                    val bmp = Bitmap.createBitmap(mutable.width, mutable.height, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    val p = Paint().apply { colorFilter = ColorMatrixColorFilter(generateColorMatrix(act.brightness, act.contrast, act.saturation)) }
                    c.drawBitmap(mutable, 0f, 0f, p)
                    mutable.recycle()
                    return bmp
                }
                is EditAction.Crop -> {
                    val r = act.rect
                    val left = r.left.toInt().coerceIn(0, mutable.width)
                    val top = r.top.toInt().coerceIn(0, mutable.height)
                    val w = r.width().toInt().coerceIn(0, mutable.width - left)
                    val h = r.height().toInt().coerceIn(0, mutable.height - top)
                    if (w > 0 && h > 0) {
                        val cropped = Bitmap.createBitmap(mutable, left, top, w, h)
                        mutable.recycle()
                        return cropped
                    }
                }
                else -> {}
            }
        }
        return mutable
    }

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (lastPointerCount < 2) return false
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f)
            val inverseMatrix = Matrix()
            imageMatrix.invert(inverseMatrix)
            val focusPoint = floatArrayOf(detector.focusX, detector.focusY)
            val imagePoint = floatArrayOf(0f, 0f)
            inverseMatrix.mapPoints(imagePoint, focusPoint)
            updateImageMatrix()
            val screenPoint = floatArrayOf(0f, 0f)
            imageMatrix.mapPoints(screenPoint, imagePoint)
            translationX += detector.focusX - screenPoint[0]
            translationY += detector.focusY - screenPoint[1]
            updateImageMatrix()
            invalidate()
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (lastPointerCount < 2) return false
            isZooming = true
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isZooming = false
            if (scaleFactor <= 1.0f) {
                scaleFactor = 1.0f
                translationX = 0f
                translationY = 0f
                updateImageMatrix()
                invalidate()
            }
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastPointerCount = event.pointerCount
        scaleGestureDetector.onTouchEvent(event)
        if (handleMultiTouchGesture(event)) return true
        val x = event.x
        val y = event.y
        return when (currentTool) {
            ToolType.DRAW -> handleDrawTouchEvent(event)
            ToolType.CROP -> handleCropTouchEvent(event, x, y)
            else -> false
        }
    }

    private fun handleMultiTouchGesture(event: MotionEvent): Boolean {
        if (!isZooming && event.pointerCount <= 1) return false
        if (currentStrokePath != null) {
            endCurrentStroke()
        }
        if (currentTool == ToolType.CROP && (isMovingCropRect || isResizingCropRect)) {
            isMovingCropRect = false
            isResizingCropRect = false
            resizeHandle = 0
            invalidate()
        }
        if (scaleFactor > 1.0f) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> if (event.pointerCount > 1) handlePanning(event)
                MotionEvent.ACTION_POINTER_UP -> { lastFocusX = 0f; lastFocusY = 0f }
            }
        }
        return true
    }

    private fun handlePanning(event: MotionEvent) {
        val focusX = (event.getX(0) + event.getX(1)) / 2
        val focusY = (event.getY(0) + event.getY(1)) / 2
        if (lastFocusX != 0f || lastFocusY != 0f) {
            translationX += focusX - lastFocusX
            translationY += focusY - lastFocusY
        }
        lastFocusX = focusX
        lastFocusY = focusY
        updateImageMatrix()
        invalidate()
    }

    private fun handleDrawTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> beginNewStroke(event.x, event.y)
            MotionEvent.ACTION_MOVE -> continueStroke(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                continueStroke(event.x, event.y)
                endCurrentStroke()
            }
        }
        invalidate()
        return true
    }

    private fun beginNewStroke(x: Float, y: Float) {
        currentStrokePath = Path()
        currentStrokePath!!.moveTo(x, y)
        val p = Paint(paint)
        currentStrokePaint = Paint(p)
        currentStrokePoints = ArrayList()
        currentStrokePoints!!.add(PointF(x, y))
    }

    private fun continueStroke(x: Float, y: Float) {
        val pts = currentStrokePoints ?: return
        val last = pts.last()
        val dx = x - last.x
        val dy = y - last.y
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist >= 2f) {
            pts.add(PointF(x, y))
            val path = currentStrokePath ?: return
            val lx = last.x
            val ly = last.y
            path.quadTo(lx, ly, (lx + x) / 2f, (ly + y) / 2f)
        }
    }

    private fun endCurrentStroke() {
        val path = currentStrokePath ?: return
        val p = currentStrokePaint ?: return
        val copyPath = Path(path)
        val copyPaint = Paint(p)
        addAction(EditAction.Draw(copyPath, copyPaint))
        mergeStrokeIntoBase(copyPath, copyPaint)
        currentStrokePath = null
        currentStrokePaint = null
        currentStrokePoints = null
    }

    private fun mergeStrokeIntoBase(path: Path, p: Paint) {
        val bmp = baseBitmap ?: return
        val canvas = Canvas(bmp)
        val inv = Matrix()
        imageMatrix.invert(inv)
        canvas.concat(inv)
        canvas.drawPath(path, p)
    }

    private fun handleCropTouchEvent(event: MotionEvent, x: Float, y: Float): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> handleCropTouchDown(x, y)
            MotionEvent.ACTION_MOVE -> handleCropTouchMove(x, y)
            MotionEvent.ACTION_UP -> handleCropTouchUp(x, y)
            else -> false
        }
    }

    private fun handleCropTouchDown(x: Float, y: Float): Boolean {
        if (!cropRect.isEmpty) {
            resizeHandle = getResizeHandle(x, y)
            if (resizeHandle > 0) {
                isResizingCropRect = true
                when (resizeHandle) {
                    1 -> { touchOffsetX = cropRect.left - x; touchOffsetY = cropRect.top - y }
                    2 -> { touchOffsetX = cropRect.right - x; touchOffsetY = cropRect.top - y }
                    3 -> { touchOffsetX = cropRect.left - x; touchOffsetY = cropRect.bottom - y }
                    4 -> { touchOffsetX = cropRect.right - x; touchOffsetY = cropRect.bottom - y }
                }
                cropStartLeft = cropRect.left; cropStartTop = cropRect.top; cropStartRight = cropRect.right; cropStartBottom = cropRect.bottom
                return true
            }
        }
        if (cropRect.contains(x, y)) {
            validateAndCorrectCropRect()
            isMovingCropRect = true
            touchOffsetX = cropRect.left - x
            touchOffsetY = cropRect.top - y
            cropStartX = x; cropStartY = y
            cropStartLeft = cropRect.left; cropStartTop = cropRect.top; cropStartRight = cropRect.right; cropStartBottom = cropRect.bottom
            return true
        }
        if (cropRect.isEmpty && isCropModeActive) {
            isCropping = true
            cropRect.set(x, y, x, y)
            return true
        }
        return true
    }

    private fun handleCropTouchMove(x: Float, y: Float): Boolean {
        if (isResizingCropRect) {
            resizeCropRect(x, y)
            clampCropRectToBounds()
            invalidate()
        } else if (isMovingCropRect) {
            moveCropRect(x, y)
        } else if (isCropping) {
            updateCropRect(x, y)
            clampCropRectToBounds()
            invalidate()
        }
        return true
    }

    private fun handleCropTouchUp(x: Float, y: Float): Boolean {
        if (isCropping) {
            enforceAspectRatio()
            updateCropRect(x, y)
        }
        isCropping = false
        isMovingCropRect = false
        isResizingCropRect = false
        resizeHandle = 0
        return true
    }

    private fun getResizeHandle(x: Float, y: Float): Int {
        val hitRadius = 48f * density
        if (hypot(x - cropRect.left, y - cropRect.top) <= hitRadius) return 1
        if (hypot(x - cropRect.right, y - cropRect.top) <= hitRadius) return 2
        if (hypot(x - cropRect.left, y - cropRect.bottom) <= hitRadius) return 3
        if (hypot(x - cropRect.right, y - cropRect.bottom) <= hitRadius) return 4
        return 0
    }

    private fun resizeCropRect(x: Float, y: Float) {
        val targetX = x + touchOffsetX
        val targetY = y + touchOffsetY
        val aspect = getAspectRatio()
        if (aspect != null) resizeCropRectWithAspectRatio(targetX, targetY, aspect) else resizeCropRectFreeform(targetX, targetY)
    }

    private fun getAspectRatio(): Float? {
        return when (currentCropMode) {
            com.tamad.editss.CropMode.SQUARE -> 1f
            com.tamad.editss.CropMode.PORTRAIT -> 9f / 16f
            com.tamad.editss.CropMode.LANDSCAPE -> 16f / 9f
            else -> null
        }
    }

    private fun resizeCropRectWithAspectRatio(x: Float, y: Float, aspectRatio: Float) {
        val (fixedX, fixedY) = getFixedCorner()
        val (newWidth, newHeight) = calculateAspectRatioSize(x, y, fixedX, fixedY, aspectRatio)
        val (cw, ch) = applySizeConstraints(newWidth, newHeight, fixedX, fixedY, aspectRatio)
        applyCropRectResize(cw, ch, fixedX, fixedY)
    }

    private fun getFixedCorner(): Pair<Float, Float> {
        return when (resizeHandle) {
            1 -> Pair(cropRect.right, cropRect.bottom)
            2 -> Pair(cropRect.left, cropRect.bottom)
            3 -> Pair(cropRect.right, cropRect.top)
            4 -> Pair(cropRect.left, cropRect.top)
            else -> Pair(cropRect.left, cropRect.top)
        }
    }

    private fun calculateAspectRatioSize(x: Float, y: Float, fixedX: Float, fixedY: Float, aspectRatio: Float): Pair<Float, Float> {
        val signX = if (resizeHandle == 1 || resizeHandle == 3) -1 else 1
        val signY = if (resizeHandle == 1 || resizeHandle == 2) -1 else 1
        var newWidth = (x - fixedX) * signX
        var newHeight = (y - fixedY) * signY
        newWidth = newWidth.coerceAtLeast(0f)
        newHeight = newHeight.coerceAtLeast(0f)
        if (newWidth / newHeight > aspectRatio) newWidth = newHeight * aspectRatio else newHeight = newWidth / aspectRatio
        return Pair(newWidth, newHeight)
    }

    private fun applySizeConstraints(newWidth: Float, newHeight: Float, fixedX: Float, fixedY: Float, aspectRatio: Float): Pair<Float, Float> {
        val minCropSize = 50f
        val minHeight = if (aspectRatio > 1) minCropSize else minCropSize / aspectRatio
        val minWidth = if (aspectRatio < 1) minCropSize else minCropSize * aspectRatio
        var cw = newWidth.coerceAtLeast(minWidth)
        var ch = newHeight.coerceAtLeast(minHeight)
        val visibleBounds = getVisibleImageBounds()
        val maxAllowedWidth = when (resizeHandle) { 1, 3 -> fixedX - visibleBounds.left else -> visibleBounds.right - fixedX }
        val maxAllowedHeight = when (resizeHandle) { 1, 2 -> fixedY - visibleBounds.top else -> visibleBounds.bottom - fixedY }
        if (cw > maxAllowedWidth || ch > maxAllowedHeight) {
            val widthScale = maxAllowedWidth / cw
            val heightScale = maxAllowedHeight / ch
            val scale = min(widthScale, heightScale)
            cw *= scale
            ch *= scale
        }
        return Pair(cw, ch)
    }

    private fun applyCropRectResize(width: Float, height: Float, fixedX: Float, fixedY: Float) {
        when (resizeHandle) {
            1 -> cropRect.set(fixedX - width, fixedY - height, fixedX, fixedY)
            2 -> cropRect.set(fixedX, fixedY - height, fixedX + width, fixedY)
            3 -> cropRect.set(fixedX - width, fixedY, fixedX, fixedY + height)
            4 -> cropRect.set(fixedX, fixedY, fixedX + width, fixedY + height)
        }
    }

    private fun resizeCropRectFreeform(x: Float, y: Float) {
        val minCropSize = 50f
        when (resizeHandle) {
            1 -> { cropRect.left = x.coerceAtMost(cropRect.right - minCropSize); cropRect.top = y.coerceAtMost(cropRect.bottom - minCropSize) }
            2 -> { cropRect.right = x.coerceAtLeast(cropRect.left + minCropSize); cropRect.top = y.coerceAtMost(cropRect.bottom - minCropSize) }
            3 -> { cropRect.left = x.coerceAtMost(cropRect.right - minCropSize); cropRect.bottom = y.coerceAtLeast(cropRect.top + minCropSize) }
            4 -> { cropRect.right = x.coerceAtLeast(cropRect.left + minCropSize); cropRect.bottom = y.coerceAtLeast(cropRect.top + minCropSize) }
        }
    }

    private fun updateCropRect(x: Float, y: Float) {
        cropRect.right = max(x, cropRect.left)
        cropRect.bottom = max(y, cropRect.top)
        val startX = cropRect.left
        val startY = cropRect.top
        val targetAspectRatio: Float? = when (currentCropMode) {
            com.tamad.editss.CropMode.SQUARE -> 1f
            com.tamad.editss.CropMode.PORTRAIT -> 9f / 16f
            com.tamad.editss.CropMode.LANDSCAPE -> 16f / 9f
            else -> null
        }
        if (targetAspectRatio != null) {
            var newWidth = x - startX
            var newHeight = y - startY
            if (newWidth / newHeight > targetAspectRatio) newHeight = newWidth / targetAspectRatio else newWidth = newHeight * targetAspectRatio
            cropRect.right = startX + newWidth
            cropRect.bottom = startY + newHeight
        } else {
            cropRect.right = x
            cropRect.bottom = y
        }
    }

    private fun moveCropRect(x: Float, y: Float) {
        val newLeft = x + touchOffsetX
        val newTop = y + touchOffsetY
        val width = cropRect.width()
        val height = cropRect.height()
        var left = newLeft
        var top = newTop
        var right = left + width
        var bottom = top + height
        val visibleBounds = getVisibleImageBounds()
        if (left < visibleBounds.left) { left = visibleBounds.left; right = left + width }
        if (top < visibleBounds.top) { top = visibleBounds.top; bottom = top + height }
        if (right > visibleBounds.right) { right = visibleBounds.right; left = right - width }
        if (bottom > visibleBounds.bottom) { bottom = visibleBounds.bottom; top = bottom - height }
        cropRect.set(left, top, right, bottom)
        clampCropRectToBounds()
        invalidate()
    }

    private fun enforceAspectRatio() {
        if (currentCropMode == com.tamad.editss.CropMode.FREEFORM || cropRect.isEmpty) return
        val targetAspectRatio = when (currentCropMode) {
            com.tamad.editss.CropMode.SQUARE -> 1f
            com.tamad.editss.CropMode.PORTRAIT -> 9f / 16f
            com.tamad.editss.CropMode.LANDSCAPE -> 16f / 9f
            else -> return
        }
        val visibleBounds = getVisibleImageBounds()
        val width = cropRect.width()
        var height = width / targetAspectRatio
        if (cropRect.top + height > visibleBounds.bottom) height = visibleBounds.bottom - cropRect.top
        cropRect.bottom = cropRect.top + height
        invalidate()
    }

    private fun validateAndCorrectCropRect() {
        if (cropRect.isEmpty) return
        val targetAspectRatio: Float? = when (currentCropMode) {
            com.tamad.editss.CropMode.SQUARE -> 1f
            com.tamad.editss.CropMode.PORTRAIT -> 9f / 16f
            com.tamad.editss.CropMode.LANDSCAPE -> 16f / 9f
            else -> null
        }
        val visibleBounds = getVisibleImageBounds()
        if (visibleBounds.width() <= 0 || visibleBounds.height() <= 0) return
        val centerX = cropRect.centerX().coerceIn(visibleBounds.left, visibleBounds.right)
        val centerY = cropRect.centerY().coerceIn(visibleBounds.top, visibleBounds.bottom)
        var width = cropRect.width()
        var height = cropRect.height()
        targetAspectRatio?.let { ratio ->
            if (width / height > ratio) width = height * ratio else height = width / ratio
        }
        val maxAllowedWidth = 2 * min(centerX - visibleBounds.left, visibleBounds.right - centerX)
        val maxAllowedHeight = 2 * min(centerY - visibleBounds.top, visibleBounds.bottom - centerY)
        if (width > maxAllowedWidth || height > maxAllowedHeight) {
            val widthScale = maxAllowedWidth / width
            val heightScale = maxAllowedHeight / height
            val scale = min(widthScale, heightScale)
            width *= scale
            height *= scale
        }
        cropRect.set(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        baseBitmap?.let {
            canvas.save()
            canvas.clipRect(imageBounds)
            if (isSketchMode) {
                canvas.drawColor(Color.WHITE)
            } else if (it.hasAlpha()) {
                checkerDrawable.draw(canvas)
            }
            canvas.drawBitmap(it, imageMatrix, imagePaint)
            canvas.restore()
        }
        currentStrokePath?.let { path ->
            currentStrokePaint?.let { p ->
                canvas.drawPath(path, p)
            }
        }
        if (currentTool == ToolType.CROP && !cropRect.isEmpty) drawCropOverlay(canvas)
    }

    private fun drawCropOverlay(canvas: Canvas) {
        overlayPath.reset()
        overlayPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        overlayPath.addRect(cropRect, Path.Direction.CCW)
        canvas.drawPath(overlayPath, overlayPaint)
        canvas.drawRect(cropRect, cropPaint)
        val cornerSize = 30f
        canvas.drawRect(cropRect.left, cropRect.top, cropRect.left + cornerSize, cropRect.top + cornerSize, cropCornerPaint)
        canvas.drawRect(cropRect.right - cornerSize, cropRect.top, cropRect.right, cropRect.top + cornerSize, cropCornerPaint)
        canvas.drawRect(cropRect.left, cropRect.bottom - cornerSize, cropRect.left + cornerSize, cropRect.bottom, cropCornerPaint)
        canvas.drawRect(cropRect.right - cornerSize, cropRect.bottom - cornerSize, cropRect.right, cropRect.bottom, cropCornerPaint)
    }

    private fun updateImageMatrix() {
        baseBitmap?.let {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val bitmapWidth = it.width.toFloat()
            val bitmapHeight = it.height.toFloat()
            val baseScale = if (bitmapWidth / viewWidth > bitmapHeight / viewHeight) viewWidth / bitmapWidth else viewHeight / bitmapHeight
            val baseDx = (viewWidth - bitmapWidth * baseScale) / 2f
            val baseDy = (viewHeight - bitmapHeight * baseScale) / 2f
            imageMatrix.setScale(baseScale * scaleFactor, baseScale * scaleFactor)
            imageMatrix.postTranslate(baseDx + translationX, baseDy + translationY)
            imageBounds.set(0f, 0f, bitmapWidth, bitmapHeight)
            imageMatrix.mapRect(imageBounds)
            imageBounds.roundOut(checkerDrawable.bounds)
        }
    }

    private fun initializeDefaultCropRect() {
        if (imageBounds.width() > 0 && imageBounds.height() > 0) {
            val visibleBounds = getVisibleImageBounds()
            if (visibleBounds.width() > 0 && visibleBounds.height() > 0) {
                var width: Float
                var height: Float
                when (currentCropMode) {
                    com.tamad.editss.CropMode.FREEFORM -> { width = visibleBounds.width(); height = visibleBounds.height() }
                    com.tamad.editss.CropMode.SQUARE -> { val size = min(visibleBounds.width(), visibleBounds.height()); width = size; height = size }
                    com.tamad.editss.CropMode.PORTRAIT -> {
                        height = visibleBounds.height()
                        width = height * 9 / 16f
                        if (width > visibleBounds.width()) { width = visibleBounds.width(); height = width * 16 / 9f }
                    }
                    com.tamad.editss.CropMode.LANDSCAPE -> {
                        width = visibleBounds.width()
                        height = width * 9 / 16f
                        if (height > visibleBounds.height()) { height = visibleBounds.height(); width = height * 16 / 9f }
                    }
                }
                val centerX = visibleBounds.centerX()
                val centerY = visibleBounds.centerY()
                cropRect.set(centerX - width / 2, centerY - height / 2, centerX + width / 2, centerY + height / 2)
                clampCropRectToBounds()
            }
        }
    }

    private fun getVisibleImageBounds(): RectF {
        val visibleLeft = max(imageBounds.left, 0f)
        val visibleTop = max(imageBounds.top, 0f)
        val visibleRight = min(imageBounds.right, width.toFloat())
        val visibleBottom = min(imageBounds.bottom, height.toFloat())
        return RectF(visibleLeft, visibleTop, visibleRight, visibleBottom)
    }

    private fun clampCropRectToBounds() {
        if (imageBounds.width() <= 0) return
        val visibleBounds = getVisibleImageBounds()
        cropRect.left = cropRect.left.coerceIn(visibleBounds.left, visibleBounds.right)
        cropRect.top = cropRect.top.coerceIn(visibleBounds.top, visibleBounds.bottom)
        cropRect.right = cropRect.right.coerceIn(visibleBounds.left, visibleBounds.right)
        cropRect.bottom = cropRect.bottom.coerceIn(visibleBounds.top, visibleBounds.bottom)
    }

    private fun cleanupHistoryStorage() {
        val removable = snapshots.keys.filter { it < actionIndex - snapshotInterval * maxSnapshots }
        removable.forEach {
            val path = snapshots.remove(it)
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    fun setSketchMode(isSketch: Boolean) {
        isSketchMode = isSketch
        invalidate()
    }

    fun getBaseBitmap(): Bitmap? = baseBitmap

    fun getDrawing(): Bitmap? {
        val b = baseBitmap ?: return null
        return b.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun getDrawingOnTransparent(): Bitmap? {
        val b = baseBitmap ?: return null
        return b.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun getTransparentDrawing(): Bitmap? {
        return getDrawingOnTransparent()
    }

    fun getTransparentDrawingWithAdjustments(): Bitmap? {
        return getFinalBitmap()
    }

    fun getSketchDrawingOnWhite(): Bitmap? {
        val drawingBitmap = getFinalBitmap() ?: return null
        val whiteBitmap = Bitmap.createBitmap(drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whiteBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(drawingBitmap, 0f, 0f, null)
        return whiteBitmap
    }

    fun getFinalBitmap(): Bitmap? {
        val src = baseBitmap ?: return null
        val hasAdjustments = brightness != 0f || contrast != 1f || saturation != 1f
        if (!hasAdjustments) return src
        val adjustedBitmap = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(adjustedBitmap)
        val paint = Paint().apply { colorFilter = imagePaint.colorFilter; isAntiAlias = true; isFilterBitmap = true; isDither = true }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return adjustedBitmap
    }

    fun convertTransparentToWhite(bitmap: Bitmap): Bitmap {
        val whiteBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whiteBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return whiteBitmap
    }

    fun setDrawingState(drawingState: DrawingState) {
        paint.color = drawingState.color
        paint.strokeWidth = drawingState.size
        paint.alpha = drawingState.opacity
    }
}
