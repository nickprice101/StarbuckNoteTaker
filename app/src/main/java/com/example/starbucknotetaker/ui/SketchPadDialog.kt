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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

private data class Stroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float,
)

private data class TextItem(
    val text: String,
    val position: Offset,
    val color: Color,
)

private sealed class DrawAction {
    data class StrokeAction(val stroke: Stroke) : DrawAction()
    data class TextAction(val textItem: TextItem) : DrawAction()
}

@Composable
fun SketchPadDialog(
    onDismiss: () -> Unit,
    onSave: (ByteArray) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colors.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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
                val density = LocalDensity.current
                val strokeWidthPx = with(density) { strokeWidthDp.dp.toPx() }
                var canvasSize by remember { mutableStateOf(IntSize.Zero) }
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
                        .height(300.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .pointerInput(isPlacingText, selectedColor, strokeWidthPx) {
                                if (isPlacingText) {
                                    detectTapGestures { offset ->
                                        val text = pendingText.trim()
                                        if (text.isNotEmpty()) {
                                            val textItem = TextItem(
                                                text = text,
                                                position = offset,
                                                color = selectedColor,
                                            )
                                            textItems.add(textItem)
                                            undoStack.add(DrawAction.TextAction(textItem))
                                            redoStack.clear()
                                        }
                                        pendingText = ""
                                        isPlacingText = false
                                    }
                                } else {
                                    var currentStrokePoints: MutableList<Offset>? = null
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val points = mutableListOf(offset)
                                            val stroke = Stroke(
                                                points = points.toList(),
                                                color = selectedColor,
                                                strokeWidth = strokeWidthPx,
                                            )
                                            currentStrokePoints = points
                                            activeStroke = stroke
                                        },
                                        onDragEnd = {
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
                                        },
                                        onDragCancel = {
                                            currentStrokePoints = null
                                            activeStroke = null
                                        },
                                        onDrag = { change, _ ->
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
                Slider(
                    value = strokeWidthDp,
                    onValueChange = { strokeWidthDp = it },
                    valueRange = 1f..20f,
                )
                if (isPlacingText) {
                    Text(
                        text = "Tap on the sketch to place your text.",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.primary,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = {
                    pendingText = ""
                    showTextDialog = true
                }) {
                    Text("Add text")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
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
                                    is DrawAction.TextAction -> {
                                        val index = textItems.indexOfLast { it == action.textItem }
                                        if (index >= 0) {
                                            textItems.removeAt(index)
                                        }
                                        redoStack.add(action)
                                    }
                                }
                            }
                        }) {
                            Text("Undo")
                        }
                        OutlinedButton(onClick = {
                            if (redoStack.isNotEmpty()) {
                                val action = redoStack.removeAt(redoStack.lastIndex)
                                when (action) {
                                    is DrawAction.StrokeAction -> {
                                        strokes.add(action.stroke)
                                        undoStack.add(action)
                                    }
                                    is DrawAction.TextAction -> {
                                        textItems.add(action.textItem)
                                        undoStack.add(action)
                                    }
                                }
                            }
                        }) {
                            Text("Redo")
                        }
                        OutlinedButton(onClick = {
                            strokes.clear()
                            textItems.clear()
                            activeStroke = null
                            undoStack.clear()
                            redoStack.clear()
                        }) {
                            Text("Clear")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Button(onClick = {
                            if (canvasSize.width <= 0 || canvasSize.height <= 0 ||
                                (strokes.isEmpty() && activeStroke == null && textItems.isEmpty())
                            ) {
                                onDismiss()
                                return@Button
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
                        }) {
                            Text("Save")
                        }
                    }
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
            }
        }
    }
}
