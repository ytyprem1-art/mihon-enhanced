package eu.kanade.tachiyomi.ui.mod.chatprofile

import android.graphics.Bitmap
import android.util.LruCache
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.mod.panelshare.awaitPanelTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChatAvatarUpload(val thumbnail: String, val photo: ByteArray, val version: String)

/** One overwritable HD document per UID, fetched only when the user opens a profile photo. */
object ChatProfilePhotoStore {
    const val COLLECTION = "chat_profile_photos"
    private val images = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    fun cached(uid: String, version: String) = images.get("$uid:$version")

    suspend fun change(uid: String, username: String, photo: ChatAvatarUpload?) {
        check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "Please sign in again" }
        val db = FirebaseFirestore.getInstance()
        val batch = db.batch()
        batch.set(
            db.collection("presence").document(uid),
            mapOf(
                "username" to username,
                "appVersion" to BuildConfig.VERSION_NAME,
                "avatarBase64" to (photo?.thumbnail ?: FieldValue.delete()),
                "avatarPhotoVersion" to (photo?.version ?: FieldValue.delete()),
            ),
            SetOptions.merge(),
        )
        val ref = db.collection(COLLECTION).document(uid)
        if (photo == null) {
            batch.delete(ref)
        } else {
            require(photo.photo.size in 1..ChatAvatarPhoto.MAX_HD_BYTES)
            require(photo.thumbnail.length <= ChatAvatarPhoto.MAX_ENCODED)
            batch.set(
                ref,
                mapOf(
                    "senderUid" to uid,
                    "version" to photo.version,
                    "width" to ChatAvatarPhoto.HD_SIDE,
                    "height" to ChatAvatarPhoto.HD_SIDE,
                    "data" to Blob.fromBytes(photo.photo),
                ),
            )
        }
        batch.commit().awaitPanelTask()
        images.snapshot().keys.filter { it.startsWith("$uid:") }.forEach { images.remove(it) }
    }

    suspend fun load(uid: String, version: String): Bitmap {
        require(uid.isNotBlank() && version.matches(Regex("[0-9a-f-]{36}"))) { "Profile photo is unavailable." }
        cached(uid, version)?.let { return it }
        val doc = FirebaseFirestore.getInstance().collection(COLLECTION).document(uid).get().awaitPanelTask()
        require(doc.getString("version") == version) { "Profile photo changed. Open the profile again." }
        val bytes = requireNotNull(doc.getBlob("data")) { "Profile photo is unavailable." }.toBytes()
        return withContext(Dispatchers.Default) {
            ChatAvatarPhoto.decodeHd(bytes)
        }.also { images.put("$uid:$version", it) }
    }
}
