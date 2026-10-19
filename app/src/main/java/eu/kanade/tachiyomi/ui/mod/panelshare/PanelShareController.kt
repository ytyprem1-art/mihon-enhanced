package eu.kanade.tachiyomi.ui.mod.panelshare

import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import eu.kanade.tachiyomi.ui.mod.EnhancedPreferences
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

data class PanelContext(
    val mangaTitle: String,
    val mangaUrl: String,
    val sourceId: Long,
    val sourceName: String,
    val sourceDomain: String?,
    val chapterName: String,
    val chapterUrl: String,
)

class PanelShareSession(val document: OriginalCropDocument, val context: PanelContext) {
    var selection by mutableStateOf(DocumentCrop(.1, .1, .9, .8))
    var template by mutableStateOf(CropTemplate.Custom)
    var preset by mutableStateOf(CropEnhancement.Original)
    var preview by mutableStateOf<Bitmap?>(null)
    var originalPreview: Bitmap? = null
    var panel by mutableStateOf<CompressedPanel?>(null)
    var caption by mutableStateOf("")
    var savedImage by mutableStateOf<android.net.Uri?>(null)
    var savedMimeType = "image/png"
    var upscaleResult by mutableStateOf<ImgUpscaleResult?>(null)
    val upscaleCache = mutableMapOf<Int, ImgUpscaleResult>()
    var useUpscale by mutableStateOf(false)
    var upscaleStatus by mutableStateOf<String?>(null)
    var upscaleJob: Job? = null
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** New export functionality reuses the existing reader hook; upstream reader files are unchanged. */
class PanelShareController {
    var session by mutableStateOf<PanelShareSession?>(null)
        private set
    private var initialLoad: Job? = null

    fun start(
        activity: ComponentActivity,
        chapter: ReaderChapter,
        pageIndex: Int,
        context: PanelContext,
        hideMenus: () -> Unit,
        pageOffset: Double = 0.0,
    ) {
        if (session != null) return
        if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            activity.toast("Screen capture is blocked by your secure screen setting")
            return
        }
        try {
            val document = OriginalCropDocument(activity, chapter, pageIndex)
            hideMenus()
            val current = PanelShareSession(document, context)
            session = current
            initialLoad = activity.lifecycleScope.launch {
                document.loadNext()
                if (session === current && document.height > 0) {
                    val top = pageOffset * document.height
                    current.selection = DocumentCrop(.1, top + .1, .9, top + .8).clamped(document.height)
                }
            }
        } catch (e: Exception) { activity.toast(e.message ?: "Reader is not ready") }
    }

    fun dismiss() {
        initialLoad?.cancel()
        initialLoad = null
        session?.upscaleJob?.cancel()
        session?.upscaleCache?.values?.forEach { it.file.delete() }
        session?.document?.close()
        session = null
    }

    suspend fun crop(current: PanelShareSession) = action(current) {
        val original = current.document.render(current.selection, 1200)
        current.originalPreview = original
        current.preset = CropEnhancement.Original
        current.preview = original
        current.upscaleCache.values.forEach { it.file.delete() }
        current.upscaleCache.clear()
        current.upscaleResult = null
        current.useUpscale = false
        current.savedImage = null
    }

    suspend fun enhance(current: PanelShareSession, preset: CropEnhancement) = action(current) {
        val original = requireNotNull(current.originalPreview)
        val result = if (preset == CropEnhancement.Original) original else withContext(Dispatchers.Default) {
            original.copy(Bitmap.Config.ARGB_8888, true).also { it.applyCropEnhancement(preset) }
        }
        current.preview = result
        current.preset = preset
        current.useUpscale = false
        current.savedImage = null
    }

    suspend fun upscale(current: PanelShareSession, context: Context, scale: Int) = action(current) {
        ImgUpscaleProtocol.requireScale(scale)
        current.upscaleJob = currentCoroutineContext()[Job]
        val directory = File(context.cacheDir, "imgupscale").apply { mkdirs() }
        withContext(Dispatchers.IO) {
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        }
        val upload = File(directory, "input-${UUID.randomUUID()}.jpg")
        val output = File(directory, "result-${UUID.randomUUID()}.image")
        var accepted = false
        try {
            val cached = current.upscaleCache[scale]
            val result = if (cached != null && cached.scale == scale && cached.file.isFile) cached else {
                current.upscaleStatus = "Preparing original crop…"
                val input = current.document.render(current.selection, ImgUpscaleProtocol.MAX_INPUT_SIDE)
                try {
                    withContext(Dispatchers.IO) {
                        upload.outputStream().use { check(input.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                    }
                } finally { input.recycle() }
                ImgUpscaleClient().upscale(upload, output, scale) { status -> current.upscaleStatus = status }
            }
            val preview = withContext(Dispatchers.Default) { ImgUpscaleClient.preview(result) }
            currentCoroutineContext().ensureActive()
            current.upscaleCache[scale] = result
            current.upscaleResult = result
            current.useUpscale = true
            current.preview = preview
            current.savedImage = null
            accepted = true
        } finally {
            upload.delete()
            if (!accepted) output.delete()
            current.upscaleStatus = null
            current.upscaleJob = null
        }
    }

    fun cancelUpscale(current: PanelShareSession) {
        current.upscaleJob?.cancel()
    }

    suspend fun prepareChat(current: PanelShareSession) = action(current) {
        require(FirebaseAuth.getInstance().currentUser != null && Injekt.get<EnhancedPreferences>().globalChatUsername.get().isNotBlank()) {
            "Open Chat and set your username first"
        }
        current.panel = withContext(Dispatchers.Default) {
            PanelImages.compress(requireNotNull(current.preview), PanelSelection(0f, 0f, 1f, 1f))
        }
    }

    suspend fun export(current: PanelShareSession, context: Context, gallery: Boolean) = action(current) {
        val upscale = current.upscaleResult?.takeIf { current.useUpscale }
        if (upscale != null) {
            require(upscale.file.isFile) { "Upscaled image is no longer cached. Please upscale again." }
            val extension = when (upscale.mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
            val output = File(context.cacheImageDir.apply { mkdirs() }, "panel-export-${UUID.randomUUID()}.$extension")
            withContext(Dispatchers.IO) { upscale.file.copyTo(output) }
            if (gallery) {
                current.savedImage = withContext(Dispatchers.IO) {
                    ImageSaver(context).save(Image.Page(output::inputStream, "Panel-${System.currentTimeMillis()}", Location.Pictures.create("Crops")))
                }
                current.savedMimeType = upscale.mimeType
                context.toast("Saved in Pictures/Mihon/Crops · ${upscale.width} × ${upscale.height}")
                output.delete()
            } else {
                context.startActivity(output.getUriCompat(context).toShareIntent(context, upscale.mimeType))
            }
            return@action
        }
        val bitmap = current.document.render(current.selection)
        try {
            bitmap.applyCropEnhancement(current.preset)
            val output = withContext(Dispatchers.IO) {
                val directory = context.cacheImageDir.apply { mkdirs() }
                // Only expire our own exported cache files; a share target may read them after this dialog closes.
                directory.listFiles()?.filter { it.name.startsWith("panel-export-") && System.currentTimeMillis() - it.lastModified() > 172_800_000L }?.forEach { it.delete() }
                File(directory, "panel-export-${UUID.randomUUID()}.png").also { output ->
                    output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            }
            if (gallery) {
                current.savedImage = withContext(Dispatchers.IO) {
                    ImageSaver(context).save(Image.Page(output::inputStream, "Panel-${System.currentTimeMillis()}", Location.Pictures.create("Crops")))
                }
                current.savedMimeType = "image/png"
                context.toast("Saved in Pictures/Mihon/Crops · ${bitmap.width} × ${bitmap.height}")
                output.delete()
            } else {
                context.startActivity(output.getUriCompat(context).toShareIntent(context, "image/png"))
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun action(current: PanelShareSession, block: suspend () -> Unit) {
        if (current.busy) return
        current.busy = true
        current.error = null
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: OutOfMemoryError) { current.error = "Not enough memory. Please select a smaller area." }
        catch (e: Exception) { current.error = e.message ?: "Could not process this image" }
        finally { current.busy = false }
    }

    suspend fun send(current: PanelShareSession): Boolean {
        if (current.busy) return false
        val panel = current.panel ?: return false
        current.busy = true
        current.error = null
        try {
            val uid = requireNotNull(FirebaseAuth.getInstance().currentUser?.uid) { "Open Chat and sign in first" }
            val username = Injekt.get<EnhancedPreferences>().globalChatUsername.get()
            require(username.isNotBlank()) { "Set your username in Chat first" }
            val metadata = current.context
            ChatPanelStore.send(
                mapOf(
                    "senderName" to username,
                    "senderUid" to uid,
                    "text" to current.caption.take(200),
                    "timestamp" to FieldValue.serverTimestamp(),
                    "readBy" to mapOf(uid to System.currentTimeMillis()),
                    "isMangaShare" to true,
                    "mangaTitle" to metadata.mangaTitle,
                    "mangaUrl" to metadata.mangaUrl,
                    "sourceId" to metadata.sourceId,
                    "sourceName" to metadata.sourceName,
                    "sourceDomain" to metadata.sourceDomain,
                    "chapterName" to metadata.chapterName,
                    "chapterUrl" to metadata.chapterUrl,
                ),
                panel,
            ).awaitPanelTask()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            current.error = "Could not send: ${e.message}"
            return false
        } finally {
            current.busy = false
        }
    }
}
