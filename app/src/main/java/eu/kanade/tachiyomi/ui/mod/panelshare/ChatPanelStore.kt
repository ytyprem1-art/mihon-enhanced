package eu.kanade.tachiyomi.ui.mod.panelshare

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small panel blobs are separate from message metadata so read receipts never re-download images. */
object ChatPanelStore {
    const val PANELS = "global_chat_panels"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupMutex = Mutex()
    private val _cleanupError = MutableStateFlow<String?>(null)
    val cleanupError = _cleanupError.asStateFlow()
    private var lastNewestId: String? = null

    fun send(message: Map<String, Any?>, panel: CompressedPanel? = null): Task<Void> {
        val db = FirebaseFirestore.getInstance()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: return Tasks.forException(IllegalStateException("Open Chat and sign in first"))
        val ref = db.collection("global_chat").document()
        val batch = db.batch()
        val data = message.toMutableMap().apply { put("senderUid", uid) }
        if (panel != null) {
            require(panel.bytes.size in 1..PanelLimits.MAX_BYTES)
            require(panel.width in 1..PanelLimits.MAX_SIDE && panel.height in 1..PanelLimits.MAX_SIDE)
            data["hasPanel"] = true
            data["panelWidth"] = panel.width
            data["panelHeight"] = panel.height
            batch.set(
                db.collection(PANELS).document(ref.id),
                mapOf("senderUid" to uid, "data" to Blob.fromBytes(panel.bytes)),
            )
        }
        batch.set(ref, data)
        return batch.commit().addOnSuccessListener { requestCleanup() }
    }

    /** A fresh listener snapshot also repairs interrupted cleanup from a previous app session. */
    fun onMessagesChanged(newestId: String) {
        if (lastNewestId == newestId && _cleanupError.value == null) return
        lastNewestId = newestId
        requestCleanup()
    }

    fun requestCleanup() {
        scope.launch {
            cleanupMutex.withLock {
                try {
                    pruneOldMessages()
                    _cleanupError.value = null
                } catch (e: Exception) {
                    Log.e("ChatPanelStore", "Chat retention cleanup failed", e)
                    _cleanupError.value = "Old messages could not be removed. Check your connection and Firestore rules."
                }
            }
        }
    }

    private suspend fun pruneOldMessages() {
        val db = FirebaseFirestore.getInstance()
        val messages = db.collection("global_chat").orderBy("timestamp", Query.Direction.DESCENDING)
        ChatRetention.prune(object : ChatRetention.Store<DocumentSnapshot> {
            // Use the server, not an incomplete offline cache, to determine the retention boundary.
            override suspend fun newest(limit: Long) = messages.limit(limit).get(Source.SERVER).awaitPanelTask().documents

            override suspend fun olderThan(boundary: DocumentSnapshot, limit: Long) =
                messages.startAfter(boundary).limit(limit).get(Source.SERVER).awaitPanelTask().documents

            override suspend fun deleteWithPanels(messages: List<DocumentSnapshot>) {
                val batch = db.batch()
                messages.forEach { doc ->
                    batch.delete(doc.reference)
                    if (doc.getBoolean("hasPanel") == true) batch.delete(db.collection(PANELS).document(doc.id))
                }
                // Message + blob deletion is atomic; an interruption cannot leave orphaned panels.
                batch.commit().awaitPanelTask()
            }
        })
    }

    suspend fun load(id: String): ByteArray {
        val doc = FirebaseFirestore.getInstance().collection(PANELS).document(id).get().awaitPanelTask()
        return requireNotNull(doc.getBlob("data")) { "This image is no longer available" }.toBytes()
    }
}

internal suspend fun <T> Task<T>.awaitPanelTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
