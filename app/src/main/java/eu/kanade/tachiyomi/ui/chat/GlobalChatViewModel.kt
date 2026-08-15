package eu.kanade.tachiyomi.ui.chat

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import eu.kanade.tachiyomi.ui.mod.EnhancedPreferences
import kotlinx.coroutines.flow.update
import mihon.core.viewmodel.StateViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GlobalChatViewModel : StateViewModel<GlobalChatState>(GlobalChatState.NeedUsername) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var chatListener: ListenerRegistration? = null
    private val enhancedPreferences: EnhancedPreferences = Injekt.get()

    init {
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { Log.d("GlobalChat", "Action Success") }
                .addOnFailureListener { e -> Log.e("GlobalChat", "Firebase Action Failed", e) }
        }

        val savedUsername = enhancedPreferences.globalChatUsername.get()
        if (savedUsername.isNotBlank()) {
            mutableState.update {
                GlobalChatState.ChatRoom(
                    username = savedUsername,
                    messages = emptyList(),
                )
            }
            startListening()
        }
    }

    fun setUsername(username: String) {
        if (username.isBlank()) return
        enhancedPreferences.globalChatUsername.set(username)
        mutableState.update {
            GlobalChatState.ChatRoom(
                username = username,
                messages = emptyList(),
            )
        }
        startListening()
    }

    private fun startListening() {
        chatListener?.remove()
        chatListener = db.collection("global_chat")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("GlobalChat", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    val messages = snapshots.documents.mapNotNull { doc ->
                        val sender = doc.getString("senderName") ?: return@mapNotNull null
                        val text = doc.getString("text") ?: ""
                        val timestamp = doc.getTimestamp("timestamp")?.toDate()?.time ?: System.currentTimeMillis()
                        val isMangaShare = doc.getBoolean("isMangaShare") ?: false

                        ChatMessage(
                            id = doc.id,
                            sender = sender,
                            text = text,
                            timestamp = timestamp,
                            isMangaShare = isMangaShare,
                            mangaUrl = doc.getString("mangaUrl"),
                            sourceId = doc.getLong("sourceId"),
                            sourceDomain = doc.getString("sourceDomain"),
                            mangaTitle = doc.getString("mangaTitle"),
                            mangaCoverUrl = doc.getString("mangaCoverUrl"),
                            sourceName = doc.getString("sourceName"),
                            replyToId = doc.getString("replyToId"),
                            replyToText = doc.getString("replyToText"),
                            replyToUser = doc.getString("replyToUser"),
                        )
                    }
                    mutableState.update { state ->
                        if (state is GlobalChatState.ChatRoom) {
                            state.copy(messages = messages)
                        } else {
                            state
                        }
                    }
                }
            }
    }

    fun setReplyingTo(message: ChatMessage?) {
        mutableState.update { state ->
            if (state is GlobalChatState.ChatRoom) {
                state.copy(replyingTo = message)
            } else {
                state
            }
        }
    }

    fun sendMessage(text: String) {
        val currentState = state.value
        if (currentState is GlobalChatState.ChatRoom && text.isNotBlank()) {
            val messageData = mutableMapOf<String, Any?>(
                "senderName" to currentState.username,
                "text" to text,
                "timestamp" to FieldValue.serverTimestamp(),
                "isMangaShare" to false,
            )

            currentState.replyingTo?.let { reply ->
                messageData["replyToId"] = reply.id
                messageData["replyToText"] = if (reply.isMangaShare) "[Manga] ${reply.mangaTitle}" else reply.text
                messageData["replyToUser"] = reply.sender
            }

            db.collection("global_chat").add(messageData)
                .addOnSuccessListener {
                    Log.d("GlobalChat", "Action Success")
                    setReplyingTo(null)
                }
                .addOnFailureListener { e -> Log.e("GlobalChat", "Firebase Action Failed", e) }
        }
    }

    override fun onCleared() {
        chatListener?.remove()
    }
}

sealed interface GlobalChatState {
    data object NeedUsername : GlobalChatState
    data class ChatRoom(
        val username: String,
        val messages: List<ChatMessage>,
        val replyingTo: ChatMessage? = null,
    ) : GlobalChatState
}

data class ChatMessage(
    val id: String = "",
    val sender: String,
    val text: String,
    val timestamp: Long,
    val isMangaShare: Boolean = false,
    val mangaUrl: String? = null,
    val sourceId: Long? = null,
    val sourceDomain: String? = null,
    val mangaTitle: String? = null,
    val mangaCoverUrl: String? = null,
    val sourceName: String? = null,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToUser: String? = null,
)
