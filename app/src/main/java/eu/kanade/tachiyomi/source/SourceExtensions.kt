package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import mihon.app.di.appGraph
import tachiyomi.domain.source.model.StubSource
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun Source.getNameForMangaInfo(
    mergeSources: List<Source>? = null,
    enabledLanguages: Set<String>? = null,
): String {
    val preferences = Injekt.get<SourcePreferences>()
    val enabledLangs = enabledLanguages ?: preferences.enabledLanguages.get()
        .filterNot { it in listOf("all", "other") }.toSet()

    // SY -->
    if (!mergeSources.isNullOrEmpty()) {
        return getMergedSourcesString(mergeSources, enabledLangs)
    }
    // SY <--

    val hasOneActiveLanguages = enabledLangs.size == 1
    val isInEnabledLanguages = lang in enabledLangs
    return when {
        // For edge cases where user disables a source they got manga of in their library.
        hasOneActiveLanguages && !isInEnabledLanguages -> toString()
        // Hide the language tag when only one language is used.
        hasOneActiveLanguages && isInEnabledLanguages -> name
        else -> toString()
    }
}

// SY -->
private fun getMergedSourcesString(
    mergeSources: List<Source>,
    enabledLanguages: Set<String>,
): String {
    return if (mergeSources.size > 1) {
        mergeSources.joinToString { source ->
            val hasOneActiveLanguages = enabledLanguages.size == 1
            val isInEnabledLanguages = source.lang in enabledLanguages
            when {
                hasOneActiveLanguages && !isInEnabledLanguages -> source.toString()
                hasOneActiveLanguages && isInEnabledLanguages -> source.name
                else -> source.toString()
            }
        }
    } else {
        mergeSources.firstOrNull()?.toString().orEmpty()
    }
}
// SY <--

fun Source.isLocalOrStub(): Boolean = isLocal() || this is StubSource

fun Source.getNameForMangaInfo(context: Context): String =
    getNameForMangaInfo(enabledLanguages = context.appGraph.sourcePreferences.enabledLanguages.get())
