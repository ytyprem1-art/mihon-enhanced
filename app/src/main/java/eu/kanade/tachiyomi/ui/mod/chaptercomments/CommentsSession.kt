package eu.kanade.tachiyomi.ui.mod.chaptercomments

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Own dispatcher/pool: closing comments never cancels page downloads or reader image requests. */
class CommentsSession(baseClient: OkHttpClient) : CommentsTransport, Closeable {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    val client: OkHttpClient = baseClient.newBuilder().apply {
        // No account cookies, challenge WebViews, write interceptors, or shared disk cache.
        interceptors().clear()
        networkInterceptors().clear()
    }
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .retryOnConnectionFailure(false)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 6
                maxRequestsPerHost = 4
            },
        )
        .connectionPool(ConnectionPool())
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            if (closed.get()) throw IOException("Komentar sudah ditutup.")
            if (chain.request().method != "GET" &&
                chain.request().tag(VoteWrite::class.java) == null
            ) {
                throw IOException("Permintaan vote harus berasal dari tombol upvote.")
            }
            chain.proceed(
                chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
                    )
                    .removeHeader("Authorization").removeHeader("Cookie").build(),
            )
        }
        .build()

    override suspend fun get(url: String, referer: String?): String = getWithHeaders(url, referer, emptyMap())

    override suspend fun getWithHeaders(url: String, referer: String?, headers: Map<String, String>): String {
        val parsed = url.toHttpUrl()
        val request = Request.Builder().url(parsed).get().apply {
            // SPA servers can return Express 404 for HTML routes when Accept defaults to */*.
            header(
                "Accept",
                if (parsed.pathSegments.any { it == "api" || it == "wp-json" } ||
                    parsed.encodedPath.endsWith(".json")
                ) {
                    "application/json, */*;q=0.8"
                } else {
                    "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"
                },
            )
            referer?.let { header("Referer", it) }
            headers.forEach { (name, value) ->
                require(
                    name.lowercase() in
                        setOf("accept", "x-package-name", "x-fcm-token", "x-version-code", "x-app-secret"),
                )
                header(name, value)
            }
        }.build()
        return execute(request)
    }

    override suspend fun write(method: String, url: String, json: String, referer: String?): String {
        val parsed = url.toHttpUrl()
        require(
            method == "POST" && parsed.isHttps && (
                (parsed.host == "commento.shngm.io" && parsed.encodedPath == "/api/article") ||
                    (
                        parsed.host == "api.asurascans.com" &&
                            Regex("/api/chapters/[0-9]+/reactions").matches(parsed.encodedPath)
                        ) ||
                    (parsed.host == "api.voratoon.com" && parsed.encodedPath == "/reactions") ||
                    (
                        (
                            parsed.host == "ikiru.wtf" || parsed.host.endsWith(
                                ".ikiru.wtf",
                            ) || parsed.host == "ikiru.id" ||
                                parsed.host.endsWith(".ikiru.id")
                            ) &&
                            parsed.encodedPath == "/api/private/reaction"
                        )
                ),
        ) { "Endpoint upvote tidak didukung." }
        val request = Request.Builder().url(
            parsed,
        ).post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .tag(VoteWrite::class.java, VoteWrite()).apply {
                referer?.let {
                    header("Referer", it)
                    val origin = it.toHttpUrl()
                    header("Origin", "${origin.scheme}://${origin.host}")
                }
            }.build()
        return execute(request)
    }

    override suspend fun postReactionForm(url: String, values: Map<String, String>, referer: String): String {
        val parsed = url.toHttpUrl()
        val origin = referer.toHttpUrl()
        require(
            parsed.isHttps && parsed.host == origin.host && parsed.port == origin.port &&
                parsed.encodedPath == "/wp-admin/admin-ajax.php" && parsed.queryParameter(
                    "action",
                ) == "set_my_reaction" &&
                parsed.queryParameterNames == setOf("action"),
        ) { "Alamat reaksi formulir tidak valid." }
        require(
            values.keys == setOf("id", "reaction", "reaction_nonce") &&
                values["id"]?.toLongOrNull()?.let { it > 0 } == true &&
                values["reaction"]?.toIntOrNull()?.let { it > 0 } == true &&
                values["reaction_nonce"]?.matches(Regex("[A-Za-z0-9_-]{1,128}")) == true,
        ) { "Data reaksi formulir tidak valid." }
        val body = FormBody.Builder().apply { values.forEach { (key, value) -> add(key, value) } }.build()
        val request = Request.Builder().url(parsed).post(body).tag(VoteWrite::class.java, VoteWrite())
            .header("Referer", referer).header("Origin", "${origin.scheme}://${origin.host}")
            .header("HX-Request", "true").header("HX-Target", "response-data").header("HX-Current-URL", referer)
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        ensureActive()
        check(!closed.get()) { "Komentar sudah ditutup." }
        val call = client.newCall(request)
        calls.add(call)
        if (closed.get()) call.cancel()
        try {
            call.await().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        if (response.code == 403 ||
                            response.code == 429
                        ) {
                            "Akses dibatasi oleh situs (HTTP ${response.code}). Coba Buka web."
                        } else {
                            "Server komentar: HTTP ${response.code}"
                        },
                    )
                }
                if (request.method == "POST" && request.url.queryParameter("action") == "set_my_reaction") {
                    listOf("HX-Trigger", "HX-Trigger-After-Swap", "HX-Trigger-After-Settle").forEach { name ->
                        response.headers.values(name).firstNotNullOfOrNull {
                            htmxReactionError(it)
                        }?.let { throw IOException(it) }
                    }
                }
                val body = response.body
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Data komentar terlalu besar.")
                val buffer = okio.Buffer()
                val source = body.source()
                while (true) {
                    ensureActive()
                    val read = source.read(buffer, 8192)
                    if (read == -1L) break
                    if (buffer.size > MAX_RESPONSE_BYTES) throw IOException("Data komentar terlalu besar.")
                }
                ensureActive()
                buffer.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            }
        } finally {
            calls.remove(call)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        calls.forEach { it.cancel() }
        calls.clear()
        client.dispatcher.cancelAll()
        // TLS close may write close_notify. Never evict idle TLS sockets on the UI thread.
        CoroutineScope(Dispatchers.IO).launch { client.connectionPool.evictAll() }
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 6L * 1024 * 1024
    }
    private class VoteWrite
}
