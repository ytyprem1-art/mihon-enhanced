package eu.kanade.tachiyomi.ui.mod.appupdate

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ModVersion(val major: Int, val minor: Int, val patch: Int = 0) {
    val number: Long get() = major * 1_000_000L + minor * 1_000L + patch
    companion object {
        fun parse(text: String): ModVersion? {
            val value = text.removePrefix("Mihon Enhanced ").substringBefore('-').trim()
            if (!value.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}(\\.[0-9]{1,3})?"))) return null
            val parts = value.split('.').map(String::toInt)
            return ModVersion(parts[0], parts[1], parts.getOrElse(2) { 0 })
        }
    }
}

data class ModReleaseNote(val version: String, val notes: String)

data class ModRelease(
    val version: String,
    val fileId: String,
    val notes: String,
    val history: List<ModReleaseNote> = emptyList(),
    val historyAvailable: Boolean = true,
) {
    init {
        require(version.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}(\\.[0-9]{1,3})?")))
        require(fileId.matches(Regex("[A-Za-z0-9_-]{10,150}")))
        require(notes.length <= 4000)
    }
    val number get() = requireNotNull(ModVersion.parse(version)).number
    val downloadUrl get() = "https://drive.usercontent.google.com/download?id=$fileId&export=download"
    fun newerThan(installed: String) = number > (ModVersion.parse(installed)?.number ?: Long.MAX_VALUE)
    fun notesSince(installed: String, previous: List<ModReleaseNote>): List<ModReleaseNote> {
        val current = ModVersion.parse(installed)?.number ?: return emptyList()
        return (previous + ModReleaseNote(version, notes))
            .filter { note -> ModVersion.parse(note.version)?.number?.let { it > current && it <= number } == true }
            .associateBy {
                ModVersion.parse(it.version)!!.number
            }.toSortedMap(compareByDescending { it }).values.toList()
    }
    companion object {
        fun driveFileId(link: String): String? {
            val url = link.trim().toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" ||
                url.host !in setOf("drive.google.com", "drive.usercontent.google.com")
            ) {
                return null
            }
            val parts = url.pathSegments
            val id = if (parts.take(2) == listOf("file", "d")) parts.getOrNull(2) else url.queryParameter("id")
            return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{10,150}")) }
        }
    }
}
