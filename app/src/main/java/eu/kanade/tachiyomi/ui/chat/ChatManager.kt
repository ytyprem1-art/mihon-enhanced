package eu.kanade.tachiyomi.ui.chat

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import eu.kanade.tachiyomi.ui.mod.panelshare.ChatHistoryPaging
import eu.kanade.tachiyomi.ui.mod.panelshare.ChatPanelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@dev.zacsweers.metro.Inject
@dev.zacsweers.metro.SingleIn(dev.zacsweers.metro.AppScope::class)
class ChatManager {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var chatListener: ListenerRegistration? = null
    private var listenerGeneration = 0
    val historyPaging = ChatHistoryPaging()

    fun loadOlderMessages() {
        val uid = auth.currentUser?.uid ?: return
        if (historyPaging.requestMore()) startListening(uid)
    }

    fun resetHistoryPaging() {
        if (historyPaging.reset()) auth.currentUser?.uid?.let(::startListening)
    }

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
        val generation = ++listenerGeneration
        // Start at 50; expand the live query only when the user scrolls to older messages.
        chatListener = db.collection("global_chat")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(historyPaging.limit)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshots, e ->
                if (generation != listenerGeneration) return@addSnapshotListener
                if (e != null) {
                    Log.e("ChatManager", "Listen failed.", e)
                    historyPaging.onError()
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    historyPaging.onSnapshot(snapshots.size(), snapshots.metadata.isFromCache)
                    if (!snapshots.metadata.isFromCache && !snapshots.metadata.hasPendingWrites()) {
                        snapshots.documents.firstOrNull()?.let { ChatPanelStore.onMessagesChanged(it.id) }
                    }
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
                            chapterName = doc.getString("chapterName"),
                            chapterUrl = doc.getString("chapterUrl"),
                            readBy = readBy,
                            hasPanel = doc.getBoolean("hasPanel") ?: false,
                            isSpoiler = doc.getBoolean("isSpoiler") ?: false,
                            hasGif = doc.getBoolean("hasGif") ?: false,
                            gifUrl = doc.getString("gifUrl"),
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
        listenerGeneration++
        chatListener?.remove()
        chatListener = null
        historyPaging.clear()
        _messages.update { emptyList() }
        _unreadCount.update { 0 }
    }
}
