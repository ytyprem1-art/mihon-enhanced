package eu.kanade.tachiyomi.ui.chat

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import eu.kanade.tachiyomi.ui.mod.EnhancedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.viewmodel.StateViewModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

import kotlin.time.Duration.Companion.seconds

class GlobalChatViewModel : StateViewModel<GlobalChatState>(GlobalChatState.NeedUsername) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var chatListener: ListenerRegistration? = null
    private var presenceListener: ListenerRegistration? = null
    private val enhancedPreferences: EnhancedPreferences = Injekt.get()

    private var typingJob: Job? = null
    private var lastTypingStatus = false

    val currentUid: String?
        get() = auth.currentUser?.uid

    init {
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    Log.d("GlobalChat", "Anonymous Sign-in Success: ${it.user?.uid}")
                    checkSavedUsername()
                }
                .addOnFailureListener { e -> Log.e("GlobalChat", "Firebase Auth Failed", e) }
        } else {
            checkSavedUsername()
        }
    }

    private fun checkSavedUsername() {
        val savedUsername = enhancedPreferences.globalChatUsername.get()
        if (savedUsername.isNotBlank()) {
            mutableState.update {
                GlobalChatState.ChatRoom(
                    username = savedUsername,
                    messages = emptyList(),
                )
            }
            startListening()
            startPresenceListening()
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
        startPresenceListening()
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

                        @Suppress("UNCHECKED_CAST")
                        val readBy = doc.get("readBy") as? Map<String, Long> ?: emptyMap()

                        ChatMessage(
                            id = doc.id,
                            sender = sender,
                            senderUid = doc.getString("senderUid") ?: "",
                            text = text,
                            timestamp = timestamp,
                            isMangaShare = isMangaShare,
                            mangaUrl = doc.getString("mangaUrl"),
                            sourceId = doc.getLong("sourceId"),
                            sourceDomain = doc.getString("sourceDomain"),
                            mangaTitle = doc.getString("mangaTitle"),
                            mangaCoverUrl = doc.getString("mangaCoverUrl"),
                            sourceName = doc.getString("sourceName"),
                            replyToMessageId = doc.getString("replyToMessageId"),
                            replyToText = doc.getString("replyToText"),
                            replyToSenderName = doc.getString("replyToSenderName"),
                            readBy = readBy,
                        )
                    }
                    mutableState.update { state ->
                        if (state is GlobalChatState.ChatRoom) {
                            val unreadCount = messages.count {
                                it.senderUid != currentUid && !it.readBy.containsKey(currentUid)
                            }
                            state.copy(messages = messages, unreadCount = unreadCount)
                        } else {
                            state
                        }
                    }
                }
            }
    }

    private fun startPresenceListening() {
        presenceListener?.remove()
        presenceListener = db.collection("presence")
            .addSnapshotListener { snapshots, _ ->
                if (snapshots != null) {
                    val users = snapshots.documents.map { doc ->
                        ChatUser(
                            uid = doc.id,
                            username = doc.getString("username") ?: "Unknown",
                            isOnline = doc.getBoolean("isOnline") ?: false,
                            isTyping = doc.getBoolean("isTyping") ?: false,
                            lastSeen = doc.getLong("lastSeen") ?: 0L,
                        )
                    }
                    mutableState.update { state ->
                        if (state is GlobalChatState.ChatRoom) {
                            val typingUsers = users.filter { it.isTyping && it.uid != currentUid }
                                .joinToString(", ") { it.username }
                            state.copy(onlineUsers = users, typingIndicator = typingUsers)
                        } else {
                            state
                        }
                    }
                }
            }
    }

    fun updatePresence(isOnline: Boolean) {
        val uid = currentUid ?: return
        val username = enhancedPreferences.globalChatUsername.get()
        if (username.isBlank()) return

        val presenceData = mutableMapOf(
            "username" to username,
            "isOnline" to isOnline,
            "lastSeen" to System.currentTimeMillis(),
        )
        if (!isOnline) {
            presenceData["isTyping"] = false
            lastTypingStatus = false
        }
        db.collection("presence").document(uid).set(presenceData, com.google.firebase.firestore.SetOptions.merge())
    }

    fun setTyping(isTyping: Boolean) {
        if (lastTypingStatus == isTyping) return
        lastTypingStatus = isTyping

        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            if (isTyping) {
                updateTypingStatus(true)
                delay(3.seconds) // Debounce: reset typing status after 3 seconds of inactivity
                updateTypingStatus(false)
                lastTypingStatus = false
            } else {
                updateTypingStatus(false)
            }
        }
    }

    private fun updateTypingStatus(isTyping: Boolean) {
        val uid = currentUid ?: return
        db.collection("presence").document(uid).update("isTyping", isTyping)
    }

    fun markMessagesAsRead() {
        val uid = currentUid ?: return
        val currentState = state.value as? GlobalChatState.ChatRoom ?: return

        val unreadMessages = currentState.messages.filter {
            it.senderUid != uid && !it.readBy.containsKey(uid)
        }

        if (unreadMessages.isEmpty()) return

        val batch = db.batch()
        unreadMessages.forEach { msg ->
            val docRef = db.collection("global_chat").document(msg.id)
            batch.update(docRef, "readBy.$uid", System.currentTimeMillis())
        }
        batch.commit()
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
        val uid = currentUid ?: return
        if (currentState is GlobalChatState.ChatRoom && text.isNotBlank()) {
            val messageData = mutableMapOf<String, Any?>(
                "senderName" to currentState.username,
                "senderUid" to uid,
                "text" to text,
                "timestamp" to FieldValue.serverTimestamp(),
                "isMangaShare" to false,
                "readBy" to mapOf(uid to System.currentTimeMillis())
            )

            currentState.replyingTo?.let { reply ->
                messageData["replyToMessageId"] = reply.id
                messageData["replyToText"] = if (reply.isMangaShare) "[Manga] ${reply.mangaTitle}" else reply.text
                messageData["replyToSenderName"] = reply.sender
            }

            db.collection("global_chat").add(messageData)
                .addOnSuccessListener {
                    Log.d("GlobalChat", "Message Sent Success")
                    setReplyingTo(null)
                    setTyping(false)
                }
                .addOnFailureListener { e -> Log.e("GlobalChat", "Message Send Failed", e) }
        }
    }

    override fun onCleared() {
        chatListener?.remove()
        presenceListener?.remove()
        updatePresence(false)
    }
}

sealed interface GlobalChatState {
    data object NeedUsername : GlobalChatState
    data class ChatRoom(
        val username: String,
        val messages: List<ChatMessage>,
        val replyingTo: ChatMessage? = null,
        val onlineUsers: List<ChatUser> = emptyList(),
        val unreadCount: Int = 0,
        val typingIndicator: String? = null,
    ) : GlobalChatState
}

data class ChatUser(
    val uid: String,
    val username: String,
    val isOnline: Boolean,
    val isTyping: Boolean,
    val lastSeen: Long,
)

data class ChatMessage(
    val id: String = "",
    val sender: String,
    val senderUid: String = "",
    val text: String,
    val timestamp: Long,
    val isMangaShare: Boolean = false,
    val mangaUrl: String? = null,
    val sourceId: Long? = null,
    val sourceDomain: String? = null,
    val mangaTitle: String? = null,
    val mangaCoverUrl: String? = null,
    val sourceName: String? = null,
    val replyToMessageId: String? = null,
    val replyToText: String? = null,
    val replyToSenderName: String? = null,
    val readBy: Map<String, Long> = emptyMap(),
)
