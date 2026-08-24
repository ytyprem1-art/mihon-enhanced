package eu.kanade.tachiyomi.ui.chat

import android.app.Application
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import eu.kanade.tachiyomi.ui.mod.EnhancedPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PresenceManager(private val context: Application) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val enhancedPreferences: EnhancedPreferences by lazy { Injekt.get() }

    private var currentStatus = PresenceStatus.OFFLINE

    fun updateStatus(
        status: PresenceStatus,
        mangaTitle: String? = null,
        mangaId: String? = null,
        sourceId: Long? = null,
        chapter: String? = null
    ) {
        currentStatus = status
        writePresence()
    }

    private fun writePresence() {
        val uid = auth.currentUser?.uid ?: return
        val username = enhancedPreferences.globalChatUsername.get()
        if (username.isBlank()) return

        // Legacy invariant: isOnline is true ONLY when user is actively on the Chat screen.
        val isOnline = currentStatus == PresenceStatus.IN_CHAT

        val presenceData = mutableMapOf<String, Any?>(
            "username" to username,
            "isOnline" to isOnline,
            "lastSeen" to System.currentTimeMillis(),

            // Clean up legacy rich fields to ensure no stale data affects rendering on other devices
            "status" to FieldValue.delete(),
            "lastActiveAt" to FieldValue.delete(),
            "readingManga" to FieldValue.delete(),
            "readingMangaId" to FieldValue.delete(),
            "readingSourceId" to FieldValue.delete(),
            "readingChapter" to FieldValue.delete(),
        )

        db.collection("presence").document(uid).set(presenceData, com.google.firebase.firestore.SetOptions.merge())
            .addOnFailureListener { e -> Log.e("PresenceManager", "Failed to write presence", e) }
    }
}
