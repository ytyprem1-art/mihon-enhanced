package eu.kanade.tachiyomi.ui.mod.panelshare

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch

@Composable
fun PanelShareHost(controller: PanelShareController) {
    DisposableEffect(controller) { onDispose { controller.dismiss() } }
    val session = controller.session ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { controller.export(session, context, true) }
        else context.toast("Storage permission is needed to save to gallery")
    }
    val panel = session.panel
    if (session.preview == null) {
        CropPanelDialog(session, controller::dismiss, { scope.launch { controller.crop(session) } })
    } else if (panel == null) {
        AlertDialog(
            onDismissRequest = { if (!session.busy) controller.dismiss() },
            title = { Text("Crop & Share") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.context.mangaTitle, style = MaterialTheme.typography.titleMedium)
                    Text("${session.context.chapterName} · ${session.context.sourceName}")
                    val bitmap = requireNotNull(session.preview)
                    Image(bitmap.asImageBitmap(), "Export preview", Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CropEnhancement.entries.forEach { preset ->
                            FilterChip(
                                selected = !session.useUpscale && session.preset == preset,
                                onClick = { scope.launch { controller.enhance(session, preset) } },
                                enabled = !session.busy,
                                label = { Text(preset.label) },
                            )
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2, 4).forEach { scale ->
                            FilterChip(
                                selected = session.useUpscale && session.upscaleResult?.scale == scale,
                                onClick = { scope.launch { controller.upscale(session, context, scale) } },
                                enabled = !session.busy,
                                label = { Text("ImgUpscale ${scale}×") },
                            )
                        }
                    }
                    val upscale = session.upscaleResult?.takeIf { session.useUpscale }
                    val dimensions = upscale?.let { it.width to it.height } ?: session.document.dimensions(session.selection)
                    Text("${dimensions.first} × ${dimensions.second} · ${if (upscale != null) "ImgUpscaler ${upscale.scale}×" else "Original-resolution PNG"}")
                    Text(
                        if (upscale != null) "Input ${upscale.inputWidth} × ${upscale.inputHeight}. Select Original to compare."
                        else "ImgUpscale uploads this crop to imgupscaler.com. Online input is limited to 2048 px per side; local saves keep original resolution.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT < 29) galleryPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            else scope.launch { controller.export(session, context, true) }
                        },
                        enabled = !session.busy, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save to gallery") }
                    session.savedImage?.let { uri ->
                        Text("Saved in Pictures/Mihon/Crops", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            try {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, session.savedMimeType)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: android.content.ActivityNotFoundException) { context.toast("No image viewer is installed") }
                        }, enabled = !session.busy) { Text("Open saved image") }
                    }
                    TextButton(
                        onClick = { scope.launch { controller.export(session, context, false) } },
                        enabled = !session.busy, modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (session.useUpscale) "Share image · Upscaled" else "Share image · Original quality") }
                    TextButton(
                        onClick = { scope.launch { controller.prepareChat(session) } },
                        enabled = !session.busy, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Global Chat · Compressed") }
                    TextButton(onClick = {
                        session.preview = null; session.originalPreview = null; session.error = null
                    }, enabled = !session.busy) { Text("Adjust crop") }
                    session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    session.upscaleStatus?.let { status ->
                        Text(status)
                        TextButton(onClick = { controller.cancelUpscale(session) }) { Text("Cancel upscale") }
                    }
                    if (session.busy) CircularProgressIndicator()
                }
            },
            confirmButton = { TextButton(onClick = controller::dismiss, enabled = !session.busy) { Text("Close") } },
        )
    } else {
        val preview = remember(panel) { PanelImages.decode(panel.bytes).asImageBitmap() }
        AlertDialog(
            onDismissRequest = { if (!session.busy) session.panel = null },
            title = { Text("Share to Global Chat") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.context.mangaTitle, style = MaterialTheme.typography.titleMedium)
                    Text("${session.context.chapterName} · ${session.context.sourceName}")
                    Image(preview, "Compressed chat preview", Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit)
                    Text("${panel.width} × ${panel.height} · ${(panel.bytes.size + 999) / 1000} KB")
                    OutlinedTextField(
                        value = session.caption,
                        onValueChange = { if (it.length <= 200) session.caption = it },
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("Add a message… (optional)") },
                        supportingText = { Text("${session.caption.length}/200") }, maxLines = 4, enabled = !session.busy,
                    )
                    session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (session.busy) CircularProgressIndicator()
                }
            },
            confirmButton = {
                Button(enabled = !session.busy, onClick = {
                    scope.launch {
                        if (controller.send(session)) { controller.dismiss(); context.toast("Shared to Chat") }
                    }
                }) { Text(if (session.busy) "Sending…" else "Share") }
            },
            dismissButton = { TextButton(onClick = { session.panel = null }, enabled = !session.busy) { Text("Back") } },
        )
    }
}

@Composable
private fun CropPanelDialog(session: PanelShareSession, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val scope = rememberCoroutineScope()
    val document = session.document
    val context = LocalContext.current
    val metrics = remember(context) {
        android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(it)
        }
    }
    // Wallpaper is portrait even when editing on a landscape tablet.
    val deviceRatio = minOf(metrics.widthPixels, metrics.heightPixels).toDouble() / maxOf(metrics.widthPixels, metrics.heightPixels)
    Dialog(
        onDismissRequest = { if (!session.busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF1B1B20)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, enabled = !session.busy) { Icon(Icons.Default.Close, "Cancel crop", tint = Color.White) }
                Text("Crop & Share", color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = onConfirm, enabled = !session.busy && document.pages.isNotEmpty()) {
                    Icon(Icons.Default.Check, "Use selected panel", tint = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().background(Color(0xFF1B1B20)).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CropTemplate.entries.forEach { template ->
                    FilterChip(
                        selected = session.template == template,
                        onClick = { session.template = template },
                        enabled = !session.busy && document.pages.isNotEmpty(),
                        label = { Text(template.label, color = Color.White) },
                    )
                }
            }
            Text(
                if (session.template == CropTemplate.Custom) "Swipe to scroll · Drag the 6 handles to resize · Pinch to zoom"
                else "Fixed wallpaper frame. Drag the image to position it; pinch to zoom.",
                color = Color(0xFFD1D1DA), modifier = Modifier.fillMaxWidth().background(Color(0xFF1B1B20)).padding(12.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { OriginalCropView(it) },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    update = { view ->
                        view.onSelection = { session.selection = it }
                        view.onNeedNext = { scope.launch { document.loadNext() } }
                        view.onError = { session.error = it }
                        view.isEnabled = !session.busy
                        view.update(document, session.selection, session.template, deviceRatio)
                        // Read the observable list so newly loaded strips trigger a redraw.
                        document.pages.size
                    },
                )
                if (session.busy || document.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            Surface(color = Color(0xFF1B1B20), contentColor = Color.White) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val dimensions = if (document.pages.isNotEmpty()) document.dimensions(session.selection) else null
                    Text(
                        if (dimensions != null) "${dimensions.first} × ${dimensions.second}" else "Loading image…",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    )
                    Text(if (document.loading) "Loading…" else if (session.template == CropTemplate.Custom) "Custom crop" else "Wallpaper", style = MaterialTheme.typography.labelMedium)
                }
            }
            (session.error ?: document.error)?.let { error ->
                Row(Modifier.fillMaxWidth().background(Color(0xFF1B1B20)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = Color.White, modifier = Modifier.weight(1f))
                    if (document.error != null) TextButton(onClick = { scope.launch { document.loadNext() } }) { Text("Retry") }
                }
            }
        }
    }
}
