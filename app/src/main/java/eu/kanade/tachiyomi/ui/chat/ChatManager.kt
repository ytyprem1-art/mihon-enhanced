package eu.kanade.tachiyomi.ui.chat

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ChatManager {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var chatListener: ListenerRegistration? = null

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
        chatListener = db.collection("global_chat")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("ChatManager", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    val count = snapshots.documents.count { doc ->
                        val senderUid = doc.getString("senderUid") ?: ""
                        @Suppress("UNCHECKED_CAST")
                        val readBy = doc.get("readBy") as? Map<String, Long> ?: emptyMap()

                        senderUid != uid && !readBy.containsKey(uid)
                    }
                    _unreadCount.update { count }
                }
            }
    }

    private fun stopListening() {
        chatListener?.remove()
        chatListener = null
        _unreadCount.update { 0 }
    }
}
