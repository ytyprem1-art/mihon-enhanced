package eu.kanade.tachiyomi.ui.chat

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ChatManager {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var chatListener: ListenerRegistration? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount = _unreadCount.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val uid = firebaseAuth.currentUser?.uid
            if (uid != null) {
                startListening(uid)
            } else {
                stopListening()
            }
        }
    }

    private fun startListening(uid: String) {
        chatListener?.remove()
        // Strict limit to 50 messages to reduce read operations
        chatListener = db.collection("global_chat")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("ChatManager", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    val messageList = snapshots.documents.mapNotNull { doc ->
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
                    }.reversed() // Ascending order for UI

                    _messages.update { messageList }

                    val count = messageList.count { msg ->
                        msg.senderUid != uid && !msg.readBy.containsKey(uid)
                    }
                    _unreadCount.update { count }
                }
            }
    }

    private fun stopListening() {
        chatListener?.remove()
        chatListener = null
        _messages.update { emptyList() }
        _unreadCount.update { 0 }
    }
}
