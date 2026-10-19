package eu.kanade.tachiyomi.ui.mod.panelshare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

@Composable
fun PanelShareHost(controller: PanelShareController) {
    DisposableEffect(controller) { onDispose { controller.dismiss() } }
    val session = controller.session ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val panel = session.panel
    if (panel == null) {
        CropPanelDialog(
            session = session,
            onDismiss = controller::dismiss,
            onConfirm = { scope.launch { controller.crop(session) } },
        )
    } else {
        val preview = remember(panel) { PanelImages.decode(panel.bytes).asImageBitmap() }
        AlertDialog(
            onDismissRequest = { if (!session.busy) controller.dismiss() },
            title = { Text("Share to Global Chat") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(session.context.mangaTitle, style = MaterialTheme.typography.titleMedium)
                    Text("${session.context.chapterName} · ${session.context.sourceName}")
                    Image(
                        bitmap = preview,
                        contentDescription = "Cropped panel preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    )
                    Text("${panel.width} × ${panel.height} · ${(panel.bytes.size + 999) / 1000} KB")
                    TextButton(
                        onClick = { session.panel = null; session.error = null },
                        enabled = !session.busy,
                    ) { Text("Crop again") }
                    OutlinedTextField(
                        value = session.caption,
                        onValueChange = { if (it.length <= 200) session.caption = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Add a message… (optional)") },
                        supportingText = { Text("${session.caption.length}/200") },
                        maxLines = 4,
                        enabled = !session.busy,
                    )
                    session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (session.busy) CircularProgressIndicator()
                }
            },
            confirmButton = {
                Button(
                    enabled = !session.busy,
                    onClick = {
                        scope.launch {
                            if (controller.send(session)) {
                                controller.dismiss()
                                context.toast("Shared to Chat")
                            }
                        }
                    },
                ) { Text(if (session.busy) "Sending…" else "Share") }
            },
            dismissButton = {
                TextButton(onClick = controller::dismiss, enabled = !session.busy) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CropPanelDialog(session: PanelShareSession, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(
        onDismissRequest = { if (!session.busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, enabled = !session.busy) {
                    Icon(Icons.Default.Close, "Cancel crop", tint = Color.White)
                }
                Text("Crop & Share", color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = onConfirm, enabled = !session.busy) {
                    Icon(Icons.Default.Check, "Use selected panel", tint = Color.White)
                }
            }
            Text(
                "Drag to select a panel. Drag the corners to resize, or the box to move.",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = session.viewport
                val bitmap = remember(viewport) { viewport.asImageBitmap() }
                val width = constraints.maxWidth.toFloat()
                val height = constraints.maxHeight.toFloat()
                val scale = min(width / viewport.width, height / viewport.height)
                val imageWidth = viewport.width * scale
                val imageHeight = viewport.height * scale
                val image = Rect(
                    (width - imageWidth) / 2f,
                    (height - imageHeight) / 2f,
                    (width + imageWidth) / 2f,
                    (height + imageHeight) / 2f,
                )
                var mode by remember { mutableIntStateOf(-1) }
                var anchor by remember { mutableStateOf(Offset.Zero) }
                Image(bitmap, "Reader viewport", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(
                    Modifier.fillMaxSize().pointerInput(viewport, image, session.busy) {
                        if (session.busy) return@pointerInput
                        fun normalized(p: Offset) = Offset(
                            ((p.x - image.left) / image.width).coerceIn(0f, 1f),
                            ((p.y - image.top) / image.height).coerceIn(0f, 1f),
                        )
                        detectDragGestures(
                            onDragStart = { start ->
                                val p = normalized(start)
                                val s = session.selection
                                val corners = listOf(
                                    Offset(s.left, s.top), Offset(s.right, s.top),
                                    Offset(s.right, s.bottom), Offset(s.left, s.bottom),
                                )
                                mode = corners.indexOfFirst {
                                    abs(it.x - p.x) * image.width < 36.dp.toPx() &&
                                        abs(it.y - p.y) * image.height < 36.dp.toPx()
                                }
                                if (mode < 0) {
                                    mode = if (p.x in s.left..s.right && p.y in s.top..s.bottom) 4 else 5
                                    if (mode == 5) {
                                        anchor = p
                                        session.selection = PanelSelection(p.x, p.y, p.x, p.y)
                                    }
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val p = normalized(change.position)
                                val s = session.selection
                                session.selection = when (mode) {
                                    5 -> PanelSelection(min(anchor.x, p.x), min(anchor.y, p.y), max(anchor.x, p.x), max(anchor.y, p.y))
                                    0 -> s.copy(left = p.x.coerceAtMost(s.right - 0.01f).coerceAtLeast(0f), top = p.y.coerceAtMost(s.bottom - 0.01f).coerceAtLeast(0f))
                                    1 -> s.copy(right = p.x.coerceAtLeast(s.left + 0.01f).coerceAtMost(1f), top = p.y.coerceAtMost(s.bottom - 0.01f).coerceAtLeast(0f))
                                    2 -> s.copy(right = p.x.coerceAtLeast(s.left + 0.01f).coerceAtMost(1f), bottom = p.y.coerceAtLeast(s.top + 0.01f).coerceAtMost(1f))
                                    3 -> s.copy(left = p.x.coerceAtMost(s.right - 0.01f).coerceAtLeast(0f), bottom = p.y.coerceAtLeast(s.top + 0.01f).coerceAtMost(1f))
                                    else -> {
                                        val dx = (amount.x / image.width).coerceIn(-s.left, 1f - s.right)
                                        val dy = (amount.y / image.height).coerceIn(-s.top, 1f - s.bottom)
                                        PanelSelection(s.left + dx, s.top + dy, s.right + dx, s.bottom + dy)
                                    }
                                }
                            },
                        )
                    },
                ) {
                    val s = session.selection
                    val box = Rect(
                        image.left + s.left * image.width, image.top + s.top * image.height,
                        image.left + s.right * image.width, image.top + s.bottom * image.height,
                    )
                    val shade = Color.Black.copy(alpha = 0.6f)
                    drawRect(shade, size = Size(size.width, box.top))
                    drawRect(shade, Offset(0f, box.bottom), Size(size.width, size.height - box.bottom))
                    drawRect(shade, Offset(0f, box.top), Size(box.left, box.height))
                    drawRect(shade, Offset(box.right, box.top), Size(size.width - box.right, box.height))
                    drawRect(Color.White, box.topLeft, box.size, style = Stroke(2.dp.toPx()))
                    listOf(box.topLeft, box.topRight, box.bottomRight, box.bottomLeft).forEach {
                        drawCircle(Color.White, 6.dp.toPx(), it)
                    }
                }
                if (session.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            session.error?.let { Text(it, color = Color.White, modifier = Modifier.padding(16.dp)) }
        }
    }
}
