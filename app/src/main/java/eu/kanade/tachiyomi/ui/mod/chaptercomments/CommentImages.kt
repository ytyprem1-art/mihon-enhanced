package eu.kanade.tachiyomi.ui.mod.chaptercomments

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded popup-only media cache, shared by thumbnails, avatars, emoji and previews. */
class CommentImages(context: Context, client: OkHttpClient) : Closeable {
    private val closed = AtomicBoolean(false)
    internal val directory = File(context.cacheDir, "chapter-comment-media-${UUID.randomUUID()}")
    internal val cleanupComplete = CompletableDeferred<Unit>()
    private val disk = DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(32L * 1024 * 1024).build()
    val loader = ImageLoader.Builder(context.applicationContext)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            add(GifDecoder.Factory())
        }
        .memoryCache(MemoryCache.Builder().maxSizeBytes(12L * 1024 * 1024).build())
        .diskCache(disk)
        .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(4))
        .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(2))
        .build()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        loader.shutdown() // Cancels image jobs and clears decoded bitmaps immediately.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                disk.shutdown()
                directory.deleteRecursively()
                cleanupComplete.complete(Unit)
            } catch (exception: Exception) {
                cleanupComplete.completeExceptionally(exception)
            }
        }
    }
}
