package eu.kanade.tachiyomi.ui.mod.chaptercomments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

/** HTMX may signal a rejected action through a response header even with HTTP 200. */
internal fun htmxReactionError(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val json = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
    val event = json?.get("raiseError")
    if (event == null && !value.split(',').any { it.trim() == "raiseError" }) return null
    val message = when (event) {
        is JsonPrimitive -> event.contentOrNull
        is JsonObject -> listOf("message", "value", "detail").firstNotNullOfOrNull { (event[it] as? JsonPrimitive)?.contentOrNull }
        else -> null
    }
    return message?.let { Jsoup.parseBodyFragment(it).text().take(240) }?.takeIf { it.isNotBlank() }
        ?: "Situs menolak reaksi. Coba lagi lewat WebView."
}
