package eu.kanade.tachiyomi.ui.chat

import android.app.Application
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import eu.kanade.tachiyomi.ui.mod.EnhancedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class PresenceManager(private val context: Application) {

    companion object {
        const val RICH_PRESENCE_ENABLED = false
    }

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val enhancedPreferences: EnhancedPreferences by lazy { Injekt.get() }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var configListener: com.google.firebase.firestore.ListenerRegistration? = null

    private var monitoringEnabled = false
    private var currentStatus = PresenceStatus.OFFLINE
    private var currentMangaTitle: String? = null
    private var currentMangaId: String? = null
    private var currentSourceId: Long? = null
    private var currentChapter: String? = null

    init {
        if (RICH_PRESENCE_ENABLED) {
            startConfigListener()
        }
    }

    private fun startConfigListener() {
        configListener?.remove()
        configListener = db.collection("config").document("monitoring")
            .addSnapshotListener { snapshot, _ ->
                val enabled = snapshot?.getBoolean("enabled") ?: false
                if (monitoringEnabled != enabled) {
                    monitoringEnabled = enabled
                    if (enabled) {
                        startHeartbeat()
                    } else {
                        stopHeartbeat()
                    }
                }
            }
    }

    fun updateStatus(
        status: PresenceStatus,
        mangaTitle: String? = null,
        mangaId: String? = null,
        sourceId: Long? = null,
        chapter: String? = null
    ) {
        val wasInChat = currentStatus == PresenceStatus.IN_CHAT

        // We always update local state so that if monitoring turns on, we have the latest data
        currentStatus = status
        currentMangaTitle = mangaTitle
        currentMangaId = mangaId
        currentSourceId = sourceId
        currentChapter = chapter

        val isInChat = currentStatus == PresenceStatus.IN_CHAT

        // Always write if entering/leaving chat (legacy isOnline update)
        // Or if monitoring is enabled (rich update)
        if (isInChat || wasInChat || monitoringEnabled || status == PresenceStatus.OFFLINE) {
            writePresence()
        }
    }

    fun startHeartbeat() {
        if (!monitoringEnabled) return
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = scope.launch {
            while (true) {
                writePresence()
                delay(60.seconds)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        // Explicitly go offline when app backgrounds
        updateStatus(PresenceStatus.OFFLINE)
    }

    fun onAvToggled(enabled: Boolean) {
        // Toggle the global switch for everyone if we are an admin
        db.collection("config").document("monitoring").set(mapOf("enabled" to enabled))
    }

    private fun writePresence() {
        val uid = auth.currentUser?.uid ?: return
        val username = enhancedPreferences.globalChatUsername.get()
        if (username.isBlank()) return

        // Legacy compatibility: isOnline MUST only be true when strictly IN_CHAT.
        val isOnline = currentStatus == PresenceStatus.IN_CHAT

        val presenceData = mutableMapOf<String, Any?>(
            "username" to username,
            "isOnline" to isOnline,
            "lastSeen" to System.currentTimeMillis(),
        )

        if (monitoringEnabled && RICH_PRESENCE_ENABLED) {
            presenceData["status"] = currentStatus.name
            presenceData["lastActiveAt"] = FieldValue.serverTimestamp()

            if (currentStatus == PresenceStatus.READING) {
                presenceData["readingManga"] = currentMangaTitle
                presenceData["readingMangaId"] = currentMangaId
                presenceData["readingSourceId"] = currentSourceId
                presenceData["readingChapter"] = currentChapter
            } else {
                presenceData["readingManga"] = null
                presenceData["readingMangaId"] = null
                presenceData["readingSourceId"] = null
                presenceData["readingChapter"] = null
            }
        } else if (RICH_PRESENCE_ENABLED) {
            // Explicitly clear rich fields so legacy devices and stale AV devices don't see old data
            presenceData["status"] = null
            presenceData["lastActiveAt"] = null
            presenceData["readingManga"] = null
            presenceData["readingMangaId"] = null
            presenceData["readingSourceId"] = null
            presenceData["readingChapter"] = null
        }

        db.collection("presence").document(uid).set(presenceData, com.google.firebase.firestore.SetOptions.merge())
            .addOnFailureListener { e -> Log.e("PresenceManager", "Failed to write presence", e) }
    }
}
