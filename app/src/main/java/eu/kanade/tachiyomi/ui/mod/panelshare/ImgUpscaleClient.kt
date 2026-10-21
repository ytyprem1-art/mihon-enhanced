package eu.kanade.tachiyomi.ui.mod.panelshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit

data class ImgUpscaleResult(
    val file: File,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val scale: Int,
    val inputWidth: Int,
    val inputHeight: Int,
)

class ImgUpscaleClient {
    private val client = Injekt.get<NetworkHelper>().client.newBuilder()
        .callTimeout(90, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .apply { interceptors().removeAll { it is CloudflareInterceptor } }
        .followRedirects(false)
        .build()

    /** Work happens off the UI thread. await() cancels its OkHttp call when the user cancels. */
    suspend fun upscale(
        input: File,
        output: File,
        scale: Int,
        onProgress: (String) -> Unit,
    ): ImgUpscaleResult = withContext(Dispatchers.IO) {
        ImgUpscaleProtocol.requireScale(scale)
        val inputBounds = bounds(input)
        var complete = false
        try {
            withTimeout(360_000) {
                onProgress("Uploading crop…")
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("tool", "upscaler")
                    .addFormDataPart("mode", "batch")
                    .addFormDataPart("scaleRadio", scale.toString())
                    .addFormDataPart("file", "mihon-crop.jpg", input.asRequestBody("image/jpeg".toMediaType()))
                    .build()
                val upload = request("/api/legacy/upload").post(form).build()
                val id = ImgUpscaleProtocol.taskId(client.newCall(upload).json())
                val start = android.os.SystemClock.elapsedRealtime()
                var resultUrl: String? = null
                while (resultUrl == null) {
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        "ImgUpscaler $scale× · Waiting for result (${(android.os.SystemClock.elapsedRealtime() - start) / 1000}s)…",
                    )
                    val body = "{\"tool\":\"upscaler\",\"taskId\":\"$id\",\"scaleRadio\":\"$scale\"}"
                    val status = request(
                        "/api/legacy/status",
                    ).post(body.toRequestBody("application/json".toMediaType())).build()
                    resultUrl = ImgUpscaleProtocol.resultUrl(client.newCall(status).json())
                    if (resultUrl == null) delay(10_000)
                }
                onProgress("Downloading enhanced image…")
                download(resultUrl, output)
                val resultBounds = bounds(output)
                require(
                    resultBounds.outWidth == inputBounds.outWidth * scale &&
                        resultBounds.outHeight == inputBounds.outHeight * scale,
                ) {
                    "ImgUpscaler returned an unexpected image size. Original crop is still available."
                }
                require(resultBounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) {
                    "Unsupported result format"
                }
                complete = true
                ImgUpscaleResult(
                    output,
                    resultBounds.outWidth,
                    resultBounds.outHeight,
                    resultBounds.outMimeType,
                    scale,
                    inputBounds.outWidth,
                    inputBounds.outHeight,
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw java.io.IOException(
                "ImgUpscaler took too long. Please try again later; the original crop is still available.",
                e,
            )
        } finally {
            if (!complete) output.delete()
        }
    }

    private fun request(path: String) = Request.Builder().url(ImgUpscaleProtocol.BASE_URL + path)
        .header("Referer", ImgUpscaleProtocol.BASE_URL + "/")
        .header("Origin", ImgUpscaleProtocol.BASE_URL)

    private suspend fun Call.json(): String = await().use { response ->
        check(response.isSuccessful) {
            when (response.code) {
                401, 403 -> "ImgUpscaler requires login or blocked this request. Try the website instead."
                402, 429 -> "ImgUpscaler free limit was reached. Please try again later."
                else -> "ImgUpscaler request failed (HTTP ${response.code}). Please try again later."
            }
        }
        val body = response.body ?: error("ImgUpscaler returned an empty response")
        body.source().use { source ->
            source.request(32_769)
            require(source.buffer.size <= 32_768) { "Unexpected ImgUpscaler response" }
            source.readUtf8()
        }
    }

    private suspend fun download(url: String, output: File) {
        require(ImgUpscaleProtocol.isAllowedResultUrl(url))
        client.newCall(
            Request.Builder().url(url).header("Referer", ImgUpscaleProtocol.BASE_URL + "/").build(),
        ).await().use { response ->
            check(response.isSuccessful) { "Could not download ImgUpscaler result (HTTP ${response.code})" }
            val body = response.body ?: error("ImgUpscaler returned an empty image")
            require(body.contentLength() <= ImgUpscaleProtocol.MAX_DOWNLOAD_BYTES) { "Upscaled file is too large" }
            body.byteStream().use { input ->
                output.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= ImgUpscaleProtocol.MAX_DOWNLOAD_BYTES) { "Upscaled file is too large" }
                        out.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    companion object {
        fun bounds(file: File) = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
            require(outWidth > 0 && outHeight > 0) { "Image could not be read" }
        }

        fun preview(result: ImgUpscaleResult): Bitmap {
            var sample = 1
            while (maxOf(result.width, result.height) / sample > 1200) sample *= 2
            return requireNotNull(
                BitmapFactory.decodeFile(
                    result.file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize =
                            sample
                    },
                ),
            )
        }
    }
}
