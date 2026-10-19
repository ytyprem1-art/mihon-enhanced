package eu.kanade.tachiyomi.ui.mod.panelshare

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import eu.kanade.tachiyomi.ui.mod.EnhancedPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.resume
import kotlin.math.min

data class PanelContext(
    val mangaTitle: String,
    val mangaUrl: String,
    val sourceId: Long,
    val sourceName: String,
    val sourceDomain: String?,
    val chapterName: String,
    val chapterUrl: String,
)

class PanelShareSession(val viewport: Bitmap, val context: PanelContext) {
    var selection by mutableStateOf(PanelSelection.Initial)
    var panel by mutableStateOf<CompressedPanel?>(null)
    var caption by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** Owned by the activity; rotation/cancellation discards the temporary viewport, never uploads it. */
class PanelShareController {
    var session by mutableStateOf<PanelShareSession?>(null)
        private set
    private var capturing = false

    fun start(
        activity: ComponentActivity,
        viewer: View,
        overlay: View,
        context: PanelContext,
        hideMenus: () -> Unit,
    ) {
        if (capturing || session != null) return
        if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            activity.toast("Screen capture is blocked by your secure screen setting")
            return
        }
        if (Injekt.get<EnhancedPreferences>().globalChatUsername.get().isBlank() ||
            FirebaseAuth.getInstance().currentUser == null
        ) {
            activity.toast("Open Chat and set your username first")
            return
        }
        capturing = true
        activity.lifecycleScope.launch {
            var bitmap: Bitmap? = null
            try {
                hideMenus()
                // Hide the entire Compose overlay, including page numbers and reader controls.
                overlay.visibility = View.INVISIBLE
                delay(250) // Wait for system-bar inset changes to settle before freezing the viewport.
                check(viewer.width > 0 && viewer.height > 0) { "Reader is not ready" }
                val location = IntArray(2)
                viewer.getLocationInWindow(location)
                val rect = Rect(location[0], location[1], location[0] + viewer.width, location[1] + viewer.height)
                val scale = min(1f, 2400f / maxOf(viewer.width, viewer.height))
                bitmap = Bitmap.createBitmap(
                    (viewer.width * scale).toInt().coerceAtLeast(1),
                    (viewer.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                val target = bitmap
                val result = suspendCancellableCoroutine { continuation ->
                    PixelCopy.request(activity.window, rect, target, { status ->
                        if (continuation.isActive) continuation.resume(status)
                    }, Handler(Looper.getMainLooper()))
                }
                check(result == PixelCopy.SUCCESS) { "Could not capture the reader. Wait for the page to load and try again." }
                session = PanelShareSession(target, context)
                bitmap = null // Ownership passes to the crop session.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast(e.message ?: "Could not capture the reader")
            } finally {
                // A pending PixelCopy can still hold the destination on cancellation; let GC reclaim it.
                bitmap = null
                overlay.visibility = View.VISIBLE
                capturing = false
            }
        }
    }

    fun dismiss() {
        session = null
    }

    suspend fun crop(current: PanelShareSession) {
        if (current.busy) return
        current.busy = true
        current.error = null
        try {
            val result = withContext(Dispatchers.Default) { PanelImages.compress(current.viewport, current.selection) }
            current.panel = result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            current.error = e.message ?: "Could not crop this image"
        } finally {
            current.busy = false
        }
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
