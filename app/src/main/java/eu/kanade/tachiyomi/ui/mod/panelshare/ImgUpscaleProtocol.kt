package eu.kanade.tachiyomi.ui.mod.panelshare

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/** Isolated because this is a website workflow, not a documented public API. */
object ImgUpscaleProtocol {
    const val BASE_URL = "https://imgupscaler.com"
    const val MAX_INPUT_SIDE = 2048
    const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024

    fun requireScale(scale: Int) {
        require(scale == 2 || scale == 4) { "Choose 2× or 4×" }
    }

    fun taskId(body: String): String {
        val json = Json.parseToJsonElement(body).jsonObject
        val id = json["taskId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            json["error"]?.jsonPrimitive?.content?.take(200) ?: "ImgUpscaler did not return a processing task"
        }
        return id
    }

    /** Null means keep waiting. Failed/unknown responses must never be treated as finished images. */
    fun resultUrl(body: String): String? {
        val json = Json.parseToJsonElement(body).jsonObject
        return when (json["status"]?.jsonPrimitive?.content) {
            "success", "completed" -> {
                val urls = json["downloadUrls"]?.jsonArray.orEmpty()
                urls.map { it.jsonPrimitive.content }.firstOrNull(::isAllowedResultUrl)
                    ?: error("ImgUpscaler returned no supported result URL")
            }
            "waiting", "processing", "pending", "queued", "uploading" -> null
            "failed", "nsfw" -> error(
                json["error"]?.jsonPrimitive?.content?.take(200) ?: "ImgUpscaler could not process this image",
            )
            else -> error("ImgUpscaler returned an unexpected response. The website may have changed.")
        }
    }

    fun isAllowedResultUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (
                host == "imgupscaler.com" || host.endsWith(".imgupscaler.com") ||
                    host == "imglarger.com" || host.endsWith(".imglarger.com")
                ) &&
            !uri.path.isNullOrBlank() && !uri.path.endsWith('/')
    }.getOrDefault(false)
}
