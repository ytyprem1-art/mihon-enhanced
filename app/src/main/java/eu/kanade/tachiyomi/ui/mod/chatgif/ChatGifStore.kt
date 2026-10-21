package eu.kanade.tachiyomi.ui.mod.chatgif

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.chat.ChatMessage
import eu.kanade.tachiyomi.ui.chat.GlobalChatState
import eu.kanade.tachiyomi.ui.mod.panelshare.ChatPanelStore
import eu.kanade.tachiyomi.ui.mod.panelshare.awaitPanelTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

data class ChatGifDraft(val bytes: ByteArray, val width: Int, val height: Int, val url: String? = null)

object ChatGifStore {
    const val COLLECTION = "global_chat_gifs"
    const val MAX_BYTES = 1_000_000
    const val MAX_SIDE = 1200

    /** Only direct GIFs from known public media CDNs are shared as links. */
    fun publicGifUrl(value: String?): String? {
        val url = value?.toHttpUrlOrNull() ?: return null
        return url.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443 &&
                (it.host == "media.tenor.com" || it.host == "media.giphy.com" ||
                    it.host == "i.giphy.com" || it.host.matches(Regex("media[0-9]+\\.giphy\\.com"))) &&
                it.encodedPath.endsWith(".gif", ignoreCase = true) && value.length <= 2000 && it.fragment == null
        }?.toString()
    }

    fun validate(bytes: ByteArray, url: String? = null): ChatGifDraft {
        require(bytes.size in 14..MAX_BYTES) { "GIF terlalu besar. Maksimal 1 MB per GIF." }
        require(bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") && bytes.last() == 0x3b.toByte()) {
            "Konten keyboard bukan file GIF yang valid."
        }
        fun dimension(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        val width = dimension(6)
        val height = dimension(8)
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "Resolusi GIF terlalu besar. Maksimal 1200 × 1200." }
        var position = 13
        fun skip(count: Int) {
            require(count >= 0 && position + count <= bytes.size) { "File GIF terpotong atau rusak." }
            position += count
        }
        fun next(): Int { require(position < bytes.size) { "File GIF terpotong." }; return bytes[position++].toInt() and 255 }
        fun subBlocks() { while (true) { val size = next(); if (size == 0) break; skip(size) } }
        if (bytes[10].toInt() and 128 != 0) skip(3 * (1 shl ((bytes[10].toInt() and 7) + 1)))
        var frames = 0
        while (true) {
            when (next()) {
                0x21 -> { next(); subBlocks() }
                0x2c -> {
                    require(position + 9 <= bytes.size) { "Frame GIF tidak lengkap." }
                    val left = dimension(position)
                    val top = dimension(position + 2)
                    val frameWidth = dimension(position + 4)
                    val frameHeight = dimension(position + 6)
                    require(frameWidth > 0 && frameHeight > 0 && left + frameWidth <= width && top + frameHeight <= height) { "Ukuran frame GIF tidak valid." }
                    val packed = bytes[position + 8].toInt() and 255
                    skip(9)
                    if (packed and 128 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
                    require(next() in 2..8) { "Encoding GIF tidak valid." }
                    subBlocks()
                    frames++
                    require(frames <= 300 && frames.toLong() * width * height <= 12_000_000) { "GIF terlalu kompleks. Pilih GIF yang lebih pendek atau kecil." }
                }
                0x3b -> { require(frames > 0 && position == bytes.size) { "File GIF tidak valid." }; break }
                else -> error("File GIF rusak atau tidak didukung.")
            }
        }
        return ChatGifDraft(bytes, width, height, url?.let { requireNotNull(publicGifUrl(it)) })
    }

    fun readBounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_BYTES) { "GIF terlalu besar. Maksimal 1 MB per GIF." }
        }
        return output.toByteArray()
    }

    suspend fun prepare(context: Context, uri: Uri, link: Uri?): ChatGifDraft = withContext(Dispatchers.IO) {
        val publicUrl = publicGifUrl(link?.toString()) ?: publicGifUrl(uri.toString())
        if (publicUrl != null) {
            try { return@withContext download(publicUrl) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A broken optional link can still have a usable local GIF. */ }
        }
        require(uri.scheme == "content") { "Keyboard tidak memberikan file GIF yang bisa dibaca." }
        context.contentResolver.openInputStream(uri).use { input ->
            validate(readBounded(requireNotNull(input) { "File GIF tidak tersedia. Pilih GIF lagi." }))
        }
    }

    private fun download(url: String): ChatGifDraft {
        // Follow only validated media CDN URLs, never arbitrary redirects or authenticated WebViews.
        val client = Injekt.get<NetworkHelper>().client.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(requireNotNull(publicGifUrl(url))).build()).execute().use { response ->
            require(response.isSuccessful) { "GIF tidak tersedia (${response.code})." }
            require(response.body.contentLength() <= MAX_BYTES) { "GIF terlalu besar. Maksimal 1 MB per GIF." }
            return validate(response.body.byteStream().use(::readBounded), url)
        }
    }

    suspend fun load(message: ChatMessage): ByteArray = withContext(Dispatchers.IO) {
        val url = publicGifUrl(message.gifUrl)
        if (url != null) download(url).bytes else {
            val doc = FirebaseFirestore.getInstance().collection(COLLECTION).document(message.id).get().awaitPanelTask()
            validate(requireNotNull(doc.getBlob("data")) { "GIF sudah tidak tersedia." }.toBytes()).bytes
        }
    }

    suspend fun send(room: GlobalChatState.ChatRoom, draft: ChatGifDraft, caption: String) {
        val uid = requireNotNull(FirebaseAuth.getInstance().currentUser?.uid)
        require(room.currentUid == uid)
        validate(draft.bytes, draft.url)
        val db = FirebaseFirestore.getInstance()
        val message = db.collection("global_chat").document()
        val data = mutableMapOf<String, Any?>(
            "senderUid" to uid, "senderName" to room.username, "text" to caption.take(200).ifBlank { "[GIF]" },
            "timestamp" to FieldValue.serverTimestamp(), "isMangaShare" to false,
            "hasGif" to true, "gifWidth" to draft.width, "gifHeight" to draft.height,
            "readBy" to mapOf(uid to System.currentTimeMillis()),
        )
        room.replyingTo?.let {
            data["replyToMessageId"] = it.id
            data["replyToSenderName"] = it.sender
            data["replyToText"] = if (it.hasGif) "[GIF]" else if (it.isMangaShare) "[Manga] ${it.mangaTitle}" else it.text
        }
        val batch = db.batch()
        if (draft.url != null) data["gifUrl"] = draft.url else batch.set(
            db.collection(COLLECTION).document(message.id), mapOf("senderUid" to uid, "data" to Blob.fromBytes(draft.bytes)),
        )
        batch.set(message, data)
        batch.commit().awaitPanelTask()
        ChatPanelStore.requestCleanup()
    }
}
