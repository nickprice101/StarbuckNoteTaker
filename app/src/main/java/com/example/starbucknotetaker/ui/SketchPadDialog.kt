package com.example.starbucknotetaker.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private data class Stroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
)

private data class TextItem(
    val id: Int,
    val text: String,
    val position: Offset,
    val color: Color,
)

private sealed class DrawAction {
    data class StrokeAction(val stroke: Stroke) : DrawAction()
    data class StrokeRemoveAction(val stroke: Stroke, val index: Int) : DrawAction()
    data class TextAddAction(val textItem: TextItem) : DrawAction()
    data class TextRemoveAction(val textItem: TextItem, val index: Int) : DrawAction()
    data class TextMoveAction(val textId: Int, val from: Offset, val to: Offset) : DrawAction()
    data class TextEditAction(val textId: Int, val oldText: String, val newText: String) : DrawAction()
}

@Composable
fun SketchPadDialog(
    onDismiss: () -> Unit,
    onSave: (ByteArray) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colors.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = "Sketch",
                    style = MaterialTheme.typography.h6,
                )
                Spacer(modifier = Modifier.height(12.dp))
                val strokes = remember { mutableStateListOf<Stroke>() }
                var activeStroke by remember { mutableStateOf<Stroke?>(null) }
                val textItems = remember { mutableStateListOf<TextItem>() }
                val undoStack = remember { mutableStateListOf<DrawAction>() }
                val redoStack = remember { mutableStateListOf<DrawAction>() }
                var selectedColor by remember { mutableStateOf(Color.Black) }
                var strokeWidthDp by remember { mutableStateOf(4f) }
                var showTextDialog by remember { mutableStateOf(false) }
                var pendingText by remember { mutableStateOf("") }
                var isPlacingText by remember { mutableStateOf(false) }
                var editingTextItem by remember { mutableStateOf<TextItem?>(null) }
                var editTextValue by remember { mutableStateOf("") }
                var draggingTextId by remember { mutableStateOf<Int?>(null) }
                var textDragOffset by remember { mutableStateOf(Offset.Zero) }
                var textDragStart by remember { mutableStateOf<Offset?>(null) }
                var textIdCounter by remember { mutableStateOf(0) }
                var eraserSizeDp by remember { mutableStateOf(20f) }
                val density = LocalDensity.current
                val configuration = LocalConfiguration.current
                val canvasHeightDp = remember(configuration.screenHeightDp) {
                    val targetHeight = configuration.screenHeightDp * 0.65f
                    val minHeight = 240f
                    val maxHeight = 600f
                    targetHeight.coerceIn(minHeight, maxHeight).dp
                }
                val strokeWidthPx = with(density) { strokeWidthDp.dp.toPx() }
                val eraserRadiusPx = with(density) { eraserSizeDp.dp.toPx() }
                var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                var isEraserMode by remember { mutableStateOf(false) }
                val colorOptions = listOf(
                    Color.Black,
                    Color.Red,
                    Color.Blue,
                    Color.Green,
                    Color.Magenta,
                    Color(0xFFFFA500),
                )
                Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(canvasHeightDp)
                            .pointerInput(isPlacingText, textItems, isEraserMode) {
                                if (!isPlacingText && !isEraserMode) {
                                    detectTapGestures(onDoubleTap = { offset ->
                                        val target = textItems
                                            .asReversed()
                                            .firstOrNull { textItem ->
                                                isPointInTextItem(offset, textItem, density)
                                            }
                                        if (target != null) {
                                            editingTextItem = target
                                            editTextValue = target.text
                                        }
                                    })
                                }
                            }
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(canvasHeightDp)
                            .pointerInput(
                                isPlacingText,
                                selectedColor,
                                strokeWidthPx,
                                isEraserMode,
                                eraserRadiusPx,
                            ) {
                                if (isPlacingText) {
                                    detectTapGestures { offset ->
                                        val text = pendingText.trim()
                                        if (text.isNotEmpty()) {
                                            val textItem = TextItem(
                                                id = textIdCounter++,
                                                text = text,
                                                position = offset,
                                                color = selectedColor,
                                            )
                                            textItems.add(textItem)
                                            undoStack.add(DrawAction.TextAddAction(textItem))
                                            redoStack.clear()
                                        }
                                        pendingText = ""
                                        isPlacingText = false
                                    }
                                } else if (isEraserMode) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            performErase(
                                                offset = offset,
                                                strokes = strokes,
                                                textItems = textItems,
                                                undoStack = undoStack,
                                                redoStack = redoStack,
                                                eraserRadiusPx = eraserRadiusPx,
                                                density = density,
                                            )
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            performErase(
                                                offset = change.position,
                                                strokes = strokes,
                                                textItems = textItems,
                                                undoStack = undoStack,
                                                redoStack = redoStack,
                                                eraserRadiusPx = eraserRadiusPx,
                                                density = density,
                                            )
                                        },
                                    )
                                } else {
                                    var currentStrokePoints: MutableList<Offset>? = null
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val target = textItems
                                                .asReversed()
                                                .firstOrNull { textItem ->
                                                    isPointInTextItem(offset, textItem, density)
                                                }
                                            if (target != null) {
                                                draggingTextId = target.id
                                                textDragOffset = offset - target.position
                                                textDragStart = target.position
                                                currentStrokePoints = null
                                                activeStroke = null
                                            } else {
                                                val points = mutableListOf(offset)
                                                val stroke = Stroke(
                                                    points = points.toList(),
                                                    color = selectedColor,
                                                    strokeWidth = strokeWidthPx,
                                                )
                                                currentStrokePoints = points
                                                activeStroke = stroke
                                                draggingTextId = null
                                                textDragStart = null
                                            }
                                        },
                                        onDragEnd = {
                                            val draggedId = draggingTextId
                                            if (draggedId != null) {
                                                val index = textItems.indexOfFirst { it.id == draggedId }
                                                val startPosition = textDragStart
                                                if (index >= 0 && startPosition != null) {
                                                    val endPosition = textItems[index].position
                                                    if (endPosition != startPosition) {
                                                        undoStack.add(
                                                            DrawAction.TextMoveAction(
                                                                textId = draggedId,
                                                                from = startPosition,
                                                                to = endPosition,
                                                            ),
                                                        )
                                                        redoStack.clear()
                                                    }
                                                }
                                                draggingTextId = null
                                                textDragStart = null
                                                textDragOffset = Offset.Zero
                                            } else {
                                                val strokePoints = currentStrokePoints
                                                if (strokePoints != null && strokePoints.isNotEmpty()) {
                                                    val stroke = activeStroke
                                                    if (stroke != null) {
                                                        strokes.add(stroke)
                                                        undoStack.add(DrawAction.StrokeAction(stroke))
                                                        redoStack.clear()
                                                    }
                                                }
                                                currentStrokePoints = null
                                                activeStroke = null
                                            }
                                        },
                                        onDragCancel = {
                                            currentStrokePoints = null
                                            activeStroke = null
                                            draggingTextId = null
                                            textDragStart = null
                                            textDragOffset = Offset.Zero
                                        },
                                        onDrag = { change, _ ->
                                            val draggedId = draggingTextId
                                            if (draggedId != null) {
                                                change.consume()
                                                val index = textItems.indexOfFirst { it.id == draggedId }
                                                if (index >= 0) {
                                                    val current = textItems[index]
                                                    val newPosition = change.position - textDragOffset
                                                    textItems[index] = current.copy(position = newPosition)
                                                }
                                            } else {
                                                val strokePoints = currentStrokePoints ?: mutableListOf<Offset>().also {
                                                    currentStrokePoints = it
                                                }
                                                strokePoints.add(change.position)
                                                val updatedStroke = Stroke(
                                                    points = strokePoints.toList(),
                                                    color = selectedColor,
                                                    strokeWidth = strokeWidthPx,
                                                )
                                                activeStroke = updatedStroke
                                            }
                                        }
                                    )
                                }
                            }
                    ) {
                        canvasSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        drawRect(Color.White)
                        val allStrokes = buildList {
                            addAll(strokes)
                            activeStroke?.let { add(it) }
                        }
                        allStrokes.forEach { stroke ->
                            val points = stroke.points
                            if (points.size == 1) {
                                drawCircle(
                                    color = stroke.color,
                                    radius = stroke.strokeWidth / 2f,
                                    center = points.first(),
                                )
                            } else if (points.size > 1) {
                                drawCircle(
                                    color = stroke.color,
                                    radius = stroke.strokeWidth / 2f,
                                    center = points.first(),
                                )
                                points.zipWithNext().forEach { (start, end) ->
                                    drawLine(
                                        color = stroke.color,
                                        start = start,
                                        end = end,
                                        strokeWidth = stroke.strokeWidth,
                                        cap = StrokeCap.Round,
                                    )
                                }
                                drawCircle(
                                    color = stroke.color,
                                    radius = stroke.strokeWidth / 2f,
                                    center = points.last(),
                                )
                            }
                        }
                        textItems.forEach { textItem ->
                            drawIntoCanvas { canvas ->
                                val paint = Paint().apply {
                                    color = textItem.color.toArgb()
                                    textSize = with(density) { 16.sp.toPx() }
                                    isAntiAlias = true
                                }
                                canvas.nativeCanvas.drawText(
                                    textItem.text,
                                    textItem.position.x,
                                    textItem.position.y,
                                    paint,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Line colour")
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    colorOptions.forEach { color ->
                        val isSelected = color == selectedColor
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(color)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colors.primary else Color.LightGray,
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .clickable { selectedColor = color },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Line thickness")
                val strokeWidthLabel = "${strokeWidthDp.roundToInt()} dp"
                Slider(
                    value = strokeWidthDp,
                    onValueChange = { strokeWidthDp = it },
                    valueRange = 1f..20f,
                    modifier = Modifier.semantics {
                        contentDescription = "Line thickness slider, current thickness $strokeWidthLabel"
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .semantics {
                                contentDescription = "Line thickness preview $strokeWidthLabel"
                            },
                    ) {
                        val centerY = size.height / 2
                        drawLine(
                            color = selectedColor,
                            start = Offset(size.width * 0.1f, centerY),
                            end = Offset(size.width * 0.9f, centerY),
                            strokeWidth = strokeWidthPx,
                            cap = StrokeCap.Round,
                        )
                    }
                    Text(
                        text = strokeWidthLabel,
                        style = MaterialTheme.typography.body2,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Eraser size")
                val eraserSizeLabel = "${eraserSizeDp.roundToInt()} dp"
                Slider(
                    value = eraserSizeDp,
                    onValueChange = { eraserSizeDp = it },
                    valueRange = 5f..80f,
                    modifier = Modifier.semantics {
                        contentDescription = "Eraser size slider, current size $eraserSizeLabel"
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .semantics {
                                contentDescription = "Eraser size preview $eraserSizeLabel"
                            },
                    ) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = eraserRadiusPx.coerceAtMost(minOf(size.width, size.height) / 2f)
                        drawCircle(
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
                            radius = radius,
                            center = center,
                        )
                    }
                    Text(
                        text = eraserSizeLabel,
                        style = MaterialTheme.typography.body2,
                    )
                }
                if (isPlacingText) {
                    Text(
                        text = "Tap on the sketch to place your text.",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.primary,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tip: Drag text to move it or double-tap to edit.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIconButton(
                        onClick = {
                            pendingText = ""
                            showTextDialog = true
                            isEraserMode = false
                        },
                        icon = Icons.Filled.TextFields,
                        contentDescription = "Add text",
                        backgroundColor = MaterialTheme.colors.primary,
                    )
                    RoundIconButton(
                        onClick = {
                            if (undoStack.isNotEmpty()) {
                                val action = undoStack.removeAt(undoStack.lastIndex)
                                when (action) {
                                    is DrawAction.StrokeAction -> {
                                        val index = strokes.indexOfLast { it == action.stroke }
                                        if (index >= 0) {
                                            strokes.removeAt(index)
                                        }
                                        redoStack.add(action)
                                    }
                                    is DrawAction.StrokeRemoveAction -> {
                                        val targetIndex = action.index.coerceIn(0, strokes.size)
                                        strokes.add(targetIndex, action.stroke)
                                        redoStack.add(action)
                                    }
                                    is DrawAction.TextAddAction -> {
                                        val index = textItems.indexOfLast { it.id == action.textItem.id }
                                        if (index >= 0) {
                                            textItems.removeAt(index)
                                        }
                                        redoStack.add(action)
                                    }
                                    is DrawAction.TextRemoveAction -> {
                                        val targetIndex = action.index.coerceIn(0, textItems.size)
                                        textItems.add(targetIndex, action.textItem)
                                        redoStack.add(action)
                                    }
                                    is DrawAction.TextMoveAction -> {
                                        val index = textItems.indexOfFirst { it.id == action.textId }
                                        if (index >= 0) {
                                            textItems[index] = textItems[index].copy(position = action.from)
                                        }
                                        redoStack.add(action)
                                    }
                                    is DrawAction.TextEditAction -> {
                                        val index = textItems.indexOfFirst { it.id == action.textId }
                                        if (index >= 0) {
                                            textItems[index] = textItems[index].copy(text = action.oldText)
                                        }
                                        redoStack.add(action)
                                    }
                                }
                            }
                        },
                        icon = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        backgroundColor = MaterialTheme.colors.surface,
                        iconTint = MaterialTheme.colors.onSurface,
                    )
                    RoundIconButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                val action = redoStack.removeAt(redoStack.lastIndex)
                                when (action) {
                                    is DrawAction.StrokeAction -> {
                                        strokes.add(action.stroke)
                                        undoStack.add(action)
                                    }
                                    is DrawAction.StrokeRemoveAction -> {
                                        val targetIndex = action.index
                                        if (targetIndex in 0 until strokes.size) {
                                            strokes.removeAt(targetIndex)
                                        } else {
                                            val fallbackIndex = strokes.indexOfLast { it == action.stroke }
                                            if (fallbackIndex >= 0) {
                                                strokes.removeAt(fallbackIndex)
                                            }
                                        }
                                        undoStack.add(action)
                                    }
                                    is DrawAction.TextAddAction -> {
                                        textItems.add(action.textItem)
                                        undoStack.add(action)
                                    }
                                    is DrawAction.TextRemoveAction -> {
                                        val targetIndex = action.index
                                        if (targetIndex in 0 until textItems.size) {
                                            textItems.removeAt(targetIndex)
                                        } else {
                                            val fallbackIndex = textItems.indexOfLast { it.id == action.textItem.id }
                                            if (fallbackIndex >= 0) {
                                                textItems.removeAt(fallbackIndex)
                                            }
                                        }
                                        undoStack.add(action)
                                    }
                                    is DrawAction.TextMoveAction -> {
                                        val index = textItems.indexOfFirst { it.id == action.textId }
                                        if (index >= 0) {
                                            textItems[index] = textItems[index].copy(position = action.to)
                                        }
                                        undoStack.add(action)
                                    }
                                    is DrawAction.TextEditAction -> {
                                        val index = textItems.indexOfFirst { it.id == action.textId }
                                        if (index >= 0) {
                                            textItems[index] = textItems[index].copy(text = action.newText)
                                        }
                                        undoStack.add(action)
                                    }
                                }
                            }
                        },
                        icon = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo",
                        backgroundColor = MaterialTheme.colors.surface,
                        iconTint = MaterialTheme.colors.onSurface,
                    )
                    RoundIconButton(
                        onClick = {
                            isEraserMode = !isEraserMode
                            if (isEraserMode) {
                                isPlacingText = false
                            }
                        },
                        icon = Icons.Filled.CleaningServices,
                        contentDescription = if (isEraserMode) "Disable eraser" else "Enable eraser",
                        backgroundColor = if (isEraserMode) MaterialTheme.colors.primary else MaterialTheme.colors.surface,
                        iconTint = if (isEraserMode) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    RoundIconButton(
                        onClick = onDismiss,
                        icon = Icons.Filled.Close,
                        contentDescription = "Cancel",
                        backgroundColor = MaterialTheme.colors.surface,
                        iconTint = MaterialTheme.colors.onSurface,
                    )
                    RoundIconButton(
                        onClick = {
                            if (canvasSize.width <= 0 || canvasSize.height <= 0 ||
                                (strokes.isEmpty() && activeStroke == null && textItems.isEmpty())
                            ) {
                                onDismiss()
                                return@RoundIconButton
                            }
                            val bitmap = Bitmap.createBitmap(
                                canvasSize.width,
                                canvasSize.height,
                                Bitmap.Config.ARGB_8888,
                            )
                            val canvas = AndroidCanvas(bitmap)
                            canvas.drawColor(AndroidColor.WHITE)
                            val allStrokesForBitmap = buildList {
                                addAll(strokes)
                                activeStroke?.let { add(it) }
                            }
                            allStrokesForBitmap.forEach { stroke ->
                                val paint = Paint().apply {
                                    color = stroke.color.toArgb()
                                    style = Paint.Style.STROKE
                                    strokeJoin = Paint.Join.ROUND
                                    strokeCap = Paint.Cap.ROUND
                                    strokeWidth = stroke.strokeWidth
                                    isAntiAlias = true
                                }
                                val points = stroke.points
                                if (points.size == 1) {
                                    canvas.drawPoint(points.first().x, points.first().y, paint)
                                } else if (points.size > 1) {
                                    val path = Path().apply {
                                        moveTo(points.first().x, points.first().y)
                                        points.drop(1).forEach { point ->
                                            lineTo(point.x, point.y)
                                        }
                                    }
                                    canvas.drawPath(path, paint)
                                }
                            }
                            textItems.forEach { textItem ->
                                val paint = Paint().apply {
                                    color = textItem.color.toArgb()
                                    style = Paint.Style.FILL
                                    textSize = with(density) { 16.sp.toPx() }
                                    isAntiAlias = true
                                }
                                canvas.drawText(
                                    textItem.text,
                                    textItem.position.x,
                                    textItem.position.y,
                                    paint,
                                )
                            }
                            val output = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            onSave(output.toByteArray())
                        },
                        icon = Icons.Filled.Check,
                        contentDescription = "Save",
                        backgroundColor = MaterialTheme.colors.primary,
                    )
                }
                if (showTextDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showTextDialog = false
                            pendingText = ""
                        },
                        title = { Text("Add text") },
                        text = {
                            Column {
                                TextField(
                                    value = pendingText,
                                    onValueChange = { pendingText = it },
                                    label = { Text("Text") },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "After confirming, tap on the sketch to place the text.")
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                if (pendingText.isNotBlank()) {
                                    isPlacingText = true
                                    isEraserMode = false
                                    showTextDialog = false
                                }
                            }) {
                                Text("Place")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = {
                                showTextDialog = false
                                pendingText = ""
                            }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
                editingTextItem?.let { item ->
                    AlertDialog(
                        onDismissRequest = {
                            editingTextItem = null
                            editTextValue = ""
                        },
                        title = { Text("Edit text") },
                        text = {
                            Column {
                                TextField(
                                    value = editTextValue,
                                    onValueChange = { editTextValue = it },
                                    label = { Text("Text") },
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                val newText = editTextValue.trim()
                                val index = textItems.indexOfFirst { it.id == item.id }
                                if (index >= 0 && newText.isNotEmpty()) {
                                    val previous = textItems[index]
                                    if (previous.text != newText) {
                                        textItems[index] = previous.copy(text = newText)
                                        undoStack.add(
                                            DrawAction.TextEditAction(
                                                textId = previous.id,
                                                oldText = previous.text,
                                                newText = newText,
                                            ),
                                        )
                                        redoStack.clear()
                                    }
                                }
                                editingTextItem = null
                                editTextValue = ""
                            }) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = {
                                editingTextItem = null
                                editTextValue = ""
                            }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun isPointInTextItem(
    point: Offset,
    textItem: TextItem,
    density: Density,
    touchRadiusPx: Float = 0f,
): Boolean {
    val textSizePx = with(density) { 16.sp.toPx() }
    val paint = Paint().apply {
        textSize = textSizePx
        isAntiAlias = true
    }
    val textWidth = paint.measureText(textItem.text)
    val fontMetrics = paint.fontMetrics
    val top = textItem.position.y + fontMetrics.ascent
    val bottom = textItem.position.y + fontMetrics.descent
    val left = textItem.position.x
    val right = left + textWidth
    val padding = max(16f, touchRadiusPx)
    return point.x in (left - padding)..(right + padding) &&
        point.y in (top - padding)..(bottom + padding)
}

@Composable
private fun RoundIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    backgroundColor: Color,
    iconTint: Color = MaterialTheme.colors.onPrimary,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = backgroundColor,
        elevation = 4.dp,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
            )
        }
    }
}

private fun performErase(
    offset: Offset,
    strokes: MutableList<Stroke>,
    textItems: MutableList<TextItem>,
    undoStack: MutableList<DrawAction>,
    redoStack: MutableList<DrawAction>,
    eraserRadiusPx: Float,
    density: Density,
) {
    var hasRemoved = false
    val removedStrokes = strokes.withIndex()
        .filter { (_, stroke) ->
            stroke.points.any { point -> distanceBetween(point, offset) <= eraserRadiusPx }
        }
    if (removedStrokes.isNotEmpty()) {
        removedStrokes
            .sortedByDescending { it.index }
            .forEach { indexedValue ->
                strokes.removeAt(indexedValue.index)
                undoStack.add(DrawAction.StrokeRemoveAction(indexedValue.value, indexedValue.index))
            }
        hasRemoved = true
    }
    val removedTexts = textItems.withIndex()
        .filter { (_, textItem) ->
            isPointInTextItem(offset, textItem, density, touchRadiusPx = eraserRadiusPx)
        }
    if (removedTexts.isNotEmpty()) {
        removedTexts
            .sortedByDescending { it.index }
            .forEach { indexedValue ->
                textItems.removeAt(indexedValue.index)
                undoStack.add(DrawAction.TextRemoveAction(indexedValue.value, indexedValue.index))
            }
        hasRemoved = true
    }
    if (hasRemoved) {
        redoStack.clear()
    }
}

private fun distanceBetween(first: Offset, second: Offset): Float {
    return hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()).toFloat()
}
