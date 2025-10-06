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
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Stroke
import androidx.compose.ui.graphics.drawscope.drawPath
import androidx.compose.ui.input.pointer.awaitEachGesture
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

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
                val strokes = remember { mutableStateListOf<List<Offset>>() }
                var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
                var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                val strokeWidthPx = with(LocalDensity.current) { 4.dp.toPx() }
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
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val stroke = mutableListOf<Offset>()
                                    val down = awaitFirstDown()
                                    stroke.add(down.position)
                                    activeStroke = stroke.toList()
                                    down.consume()
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (change.pressed) {
                                            stroke.add(change.position)
                                            activeStroke = stroke.toList()
                                            change.consume()
                                        } else {
                                            change.consume()
                                            break
                                        }
                                    }
                                    if (stroke.isNotEmpty()) {
                                        strokes.add(stroke.toList())
                                    }
                                    activeStroke = emptyList()
                                }
                            }
                    ) {
                        canvasSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        drawRect(Color.White)
                        val strokeStyle = Stroke(
                            width = strokeWidthPx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        )
                        val allStrokes = strokes + activeStroke.let { if (it.isEmpty()) emptyList() else listOf(it) }
                        allStrokes.forEach { points ->
                            if (points.size == 1) {
                                drawCircle(
                                    color = Color.Black,
                                    radius = strokeWidthPx / 2f,
                                    center = points.first(),
                                )
                            } else if (points.size > 1) {
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(points.first().x, points.first().y)
                                    points.drop(1).forEach { point ->
                                        lineTo(point.x, point.y)
                                    }
                                }
                                drawPath(
                                    path = path,
                                    color = Color.Black,
                                    style = strokeStyle,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = {
                        strokes.clear()
                        activeStroke = emptyList()
                    }) {
                        Text("Clear")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        if (canvasSize.width <= 0 || canvasSize.height <= 0 ||
                            (strokes.isEmpty() && activeStroke.isEmpty())
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
                        val paint = Paint().apply {
                            color = AndroidColor.BLACK
                            style = Paint.Style.STROKE
                            strokeJoin = Paint.Join.ROUND
                            strokeCap = Paint.Cap.ROUND
                            strokeWidth = strokeWidthPx
                            isAntiAlias = true
                        }
                        val pointsToRender = strokes + activeStroke.let { if (it.isEmpty()) emptyList() else listOf(it) }
                        pointsToRender.forEach { points ->
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
                        val output = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        onSave(output.toByteArray())
                    }) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
