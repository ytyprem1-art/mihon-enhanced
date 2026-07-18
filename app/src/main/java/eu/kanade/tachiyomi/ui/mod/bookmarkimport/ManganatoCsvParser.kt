package eu.kanade.tachiyomi.ui.mod.bookmarkimport

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

object ManganatoCsvParser {

    data class BookmarkEntry(
        val id: String,
        val title: String,
        val viewedChapter: Double?, // null if unread
        val originalUrl: String,
        val domain: String?,
        val mangaPath: String?,
        val isValid: Boolean,
        val validationError: String? = null,
        val matchResult: MatchResult = MatchResult.UNCHECKED,
        val resolvedManga: tachiyomi.domain.manga.model.Manga? = null,
        val resolvedSourceName: String? = null,
    )

    enum class MatchResult {
        UNCHECKED,
        MATCHED,
        NOT_FOUND,
        SOURCE_UNAVAILABLE,
        NETWORK_TIMEOUT,
        SOURCE_ERROR,
        CANCELED
    }

    class InvalidHeaderException(message: String) : Exception(message)

    private val SUPPORTED_DOMAINS = setOf(
        "manganato.gg",
        "natomanga.com",
        "nelomanga.com",
        "nelomanga.net",
        "mangakakalot.gg",
    )

    private val MANGA_PATH_REGEX = Regex("/(manga-|manga/)[^/]+")

    fun parse(inputStream: InputStream): List<BookmarkEntry> {
        val reader = InputStreamReader(inputStream, StandardCharsets.UTF_8)
        val rawLines = readCsvLines(reader)

        if (rawLines.isEmpty()) return emptyList()

        val header = rawLines[0].map { it.removeSurrounding("\"").lowercase(Locale.ROOT).trim() }
        val requiredHeaders = listOf("id", "title", "viewed", "url")

        if (!header.containsAll(requiredHeaders)) {
            throw InvalidHeaderException("Missing required headers. Expected: ID, Title, Viewed, URL. Found: ${rawLines[0].joinToString()}")
        }

        val idIdx = header.indexOf("id")
        val titleIdx = header.indexOf("title")
        val viewedIdx = header.indexOf("viewed")
        val urlIdx = header.indexOf("url")

        val entries = mutableListOf<BookmarkEntry>()

        for (i in 1 until rawLines.size) {
            val line = rawLines[i]
            if (line.isEmpty() || (line.size == 1 && line[0].isBlank())) continue

            val rawId = line.getOrElse(idIdx) { "" }.trim()
            val rawTitle = line.getOrElse(titleIdx) { "" }.trim()
            val rawViewed = line.getOrElse(viewedIdx) { "" }.trim()
            val rawUrl = line.getOrElse(urlIdx) { "" }.trim()

            if (rawTitle.isEmpty() && rawUrl.isEmpty()) continue

            val viewedChapter = parseViewedChapter(rawViewed)
            val (domain, path, urlError) = validateAndNormalizeUrl(rawUrl)

            entries.add(
                BookmarkEntry(
                    id = rawId,
                    title = rawTitle,
                    viewedChapter = viewedChapter,
                    originalUrl = rawUrl,
                    domain = domain,
                    mangaPath = path,
                    isValid = urlError == null && rawTitle.isNotEmpty(),
                    validationError = urlError ?: if (rawTitle.isEmpty()) "Title is empty" else null
                )
            )
        }

        return entries
    }

    private fun readCsvLines(reader: InputStreamReader): List<List<String>> {
        val lines = mutableListOf<List<String>>()
        val currentLine = mutableListOf<String>()
        val currentValue = StringBuilder()
        var inQuotes = false

        var charInt = reader.read()
        if (charInt == 0xFEFF) charInt = reader.read() // Skip UTF-8 BOM

        while (charInt != -1) {
            val c = charInt.toChar()
            if (inQuotes) {
                if (c == '\"') {
                    val next = reader.read()
                    if (next != -1 && next.toChar() == '\"') {
                        currentValue.append('\"')
                    } else {
                        inQuotes = false
                        charInt = next
                        continue
                    }
                } else {
                    currentValue.append(c)
                }
            } else {
                when (c) {
                    '\"' -> inQuotes = true
                    ',' -> {
                        currentLine.add(currentValue.toString())
                        currentValue.setLength(0)
                    }
                    '\n', '\r' -> {
                        currentLine.add(currentValue.toString())
                        if (currentLine.any { it.isNotBlank() }) {
                            lines.add(ArrayList(currentLine))
                        }
                        currentLine.clear()
                        currentValue.setLength(0)
                        if (c == '\r') {
                            val next = reader.read()
                            if (next != -1 && next.toChar() != '\n') {
                                charInt = next
                                continue
                            }
                        }
                    }
                    else -> currentValue.append(c)
                }
            }
            charInt = reader.read()
        }
        if (currentLine.isNotEmpty() || currentValue.isNotEmpty()) {
            currentLine.add(currentValue.toString())
            if (currentLine.any { it.isNotBlank() }) {
                lines.add(currentLine)
            }
        }
        return lines
    }

    private fun parseViewedChapter(raw: String): Double? {
        if (raw.isBlank() || raw.lowercase(Locale.ROOT) == "none") return null

        // Remove "Chapter ", "Ch. ", etc.
        val cleaned = raw.replace(Regex("(?i)^(chapter|ch)\\.?\\s*"), "").trim()
        return cleaned.toDoubleOrNull()
    }

    private fun validateAndNormalizeUrl(url: String): Triple<String?, String?, String?> {
        if (url.isBlank()) return Triple(null, null, "URL is empty")

        return try {
            val uri = java.net.URI(url)
            val host = (uri.host ?: "").lowercase(Locale.ROOT).removePrefix("www.")

            if (host.isEmpty() || !SUPPORTED_DOMAINS.contains(host)) {
                return Triple(if (host.isEmpty()) null else host, null, "Unsupported domain: ${if (host.isEmpty()) "unknown" else host}")
            }

            val path = uri.path
            if (path == null || !MANGA_PATH_REGEX.containsMatchIn(path)) {
                return Triple(host, path, "Invalid manga URL path")
            }

            // Normalize path to /manga-xxxxxx or /manga/xxxxxx
            val match = MANGA_PATH_REGEX.find(path) ?: return Triple(host, path, "Invalid manga URL path")
            val normalizedPath = match.value

            Triple(host, normalizedPath, null)
        } catch (e: Exception) {
            Triple(null, null, "Invalid URL format")
        }
    }
}
