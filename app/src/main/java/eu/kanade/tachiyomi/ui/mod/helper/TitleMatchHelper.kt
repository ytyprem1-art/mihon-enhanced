package eu.kanade.tachiyomi.ui.mod.helper

object TitleMatchHelper {
    fun normalizeTitle(title: String): String {
        return title.lowercase()
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ")
    }

    fun isExactMatch(original: String, result: String): Boolean {
        if (original.isBlank() || result.isBlank()) return false
        return normalizeTitle(original) == normalizeTitle(result)
    }

    fun isTitleMatch(original: String, result: String): Boolean {
        if (original.isBlank() || result.isBlank()) return false
        val normOriginal = normalizeTitle(original)
        val normResult = normalizeTitle(result)

        if (normResult.startsWith(normOriginal)) {
            if (normResult.length == normOriginal.length) return true

            // Boundary detection in result's raw title
            // Since normalization removes punctuation, we need a smarter way or check raw
            val resultLower = result.lowercase()
            val originalLower = original.lowercase()

            // Check if what follows the original title is a boundary in the raw result
            val startIndex = resultLower.indexOf(originalLower)
            if (startIndex == -1) return false // Should not happen if normalized starts with

            val nextCharIndex = startIndex + originalLower.length
            if (nextCharIndex >= result.length) return true

            val nextChar = result[nextCharIndex]
            return nextChar.isWhitespace() || nextChar in ",:-("
        }
        return false
    }

    enum class MatchType {
        NONE,
        TITLE_MATCH,
        EXACT_MATCH,
    }

    fun getMatchType(original: String, result: String): MatchType {
        if (isExactMatch(original, result)) return MatchType.EXACT_MATCH
        if (isTitleMatch(original, result)) return MatchType.TITLE_MATCH
        return MatchType.NONE
    }
}
