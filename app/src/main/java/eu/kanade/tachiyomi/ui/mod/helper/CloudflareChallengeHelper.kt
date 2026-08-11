package eu.kanade.tachiyomi.ui.mod.helper

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareBypassException
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

object CloudflareChallengeHelper {

    /**
     * Identifies if the source is Manganato (English).
     */
    fun isManganato(source: Source?): Boolean {
        if (source == null) return false
        // Manganato typically has name "Manganato" and lang "en"
        return source.name == "Manganato" && (source as? HttpSource)?.lang == "en"
    }

    /**
     * Safely checks if the error is a Cloudflare bypass failure.
     */
    fun isCloudflareBypassFailure(throwable: Throwable): Boolean {
        // CloudflareInterceptor wraps CloudflareBypassException in an IOException
        if (throwable is IOException && throwable.cause is CloudflareBypassException) {
            return true
        }
        var cause = throwable
        while (cause.cause != null && cause != cause.cause) {
            if (cause is CloudflareBypassException) return true
            cause = cause.cause!!
        }
        return cause is CloudflareBypassException
    }

    /**
     * Checks if a valid-looking cf_clearance cookie exists for the target URL.
     */
    fun hasValidCfClearance(source: Source?, targetUrl: String?): Boolean {
        val httpSource = source as? HttpSource ?: return true
        val url = targetUrl?.toHttpUrlOrNull() ?: httpSource.baseUrl.toHttpUrlOrNull() ?: return true
        return try {
            val networkHelper: NetworkHelper = Injekt.get()
            val cookies = networkHelper.cookieJar.get(url)
            cookies.any { it.name == "cf_clearance" }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generates the search URL for the source.
     */
    fun getSearchUrl(source: Source?, query: String?, filters: FilterList): String? {
        val httpSource = source as? HttpSource ?: return null
        return try {
            // We use reflection to call protected searchMangaRequest
            val method = HttpSource::class.java.getDeclaredMethod(
                "searchMangaRequest",
                Int::class.javaPrimitiveType,
                String::class.java,
                FilterList::class.java
            )
            method.isAccessible = true
            val request = method.invoke(httpSource, 1, query ?: "", filters) as okhttp3.Request
            request.url.toString()
        } catch (e: Exception) {
            // Fallback for Manganato specific pattern
            if (isManganato(source) && !query.isNullOrBlank()) {
                val sanitizedQuery = query.trim().lowercase().replace(" ", "_").replace(Regex("[^a-z0-9_]"), "")
                "${httpSource.baseUrl}/search/story/$sanitizedQuery"
            } else {
                httpSource.baseUrl
            }
        }
    }
}
