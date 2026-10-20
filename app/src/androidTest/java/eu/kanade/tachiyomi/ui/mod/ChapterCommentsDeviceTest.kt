package eu.kanade.tachiyomi.ui.mod

import android.os.StrictMode
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.mod.chaptercomments.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import org.junit.Assert.*
import org.junit.Test

/** Public GETs and owned client only; no database writes or reader navigation. */
class ChapterCommentsDeviceTest {
    @Test fun actualEleceedChapter422FromInstalledExtension() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph: AppGraph = instrumentation.targetContext.metroGraph()
        val mangas = graph.enhancedMangaRepository.getLibraryManga().map { it.manga }.filter { it.title.contains("Eleceed", ignoreCase = true) }
        assertTrue("Eleceed library entry needed for reported regression", mangas.isNotEmpty())
        val details = StringBuilder()
        var verified = 0
        for (manga in mangas) {
            val chapter = graph.enhancedChapterRepository.getChapterByMangaId(manga.id, false).firstOrNull { it.chapterNumber == 422.0 } ?: continue
            val target = resolveCommentTarget(graph, chapter)
            if (!target.sourceName.contains("Shinigami", ignoreCase = true)) continue
            details.appendLine("Resolved: $target")
            val session = CommentsSession(graph.enhancedNetworkHelper.client)
            try {
                val provider = chapterCommentsProvider(target, session)
                val comments = withContext(Dispatchers.IO) { provider.comments(CommentSort.POPULAR, 1) }
                details.appendLine("Comments: ${comments.comments.size}, total: ${comments.total}")
                val reactions = withContext(Dispatchers.IO) { provider.reactions() }
                details.appendLine("Reactions: $reactions")
                verified++
            } catch (e: Exception) {
                details.appendLine(e.stackTraceToString())
                throw AssertionError(details.toString(), e)
            } finally {
                withContext(Dispatchers.IO) { java.io.File(instrumentation.targetContext.cacheDir, "comments-eleceed-test.txt").writeText(details.toString()) }
                session.close()
            }
        }
        assertTrue("No Shinigami chapter422 resolved: $details", verified > 0)
    }

    @Test fun shinigamiPublicCommentsAndClosingOnMainThread() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph: AppGraph = instrumentation.targetContext.metroGraph()
        val session = CommentsSession(graph.enhancedNetworkHelper.client)
        try {
            val provider = ShinigamiCommentsProvider(CommentTarget(
                "https://11.shinigami.asia/chapter/9637aa7b-a317-4cd7-9b2c-5cbef50594fa", "Chapter 20", "Shinigami",
            ), session)
            val page = withContext(Dispatchers.IO) { provider.comments(CommentSort.POPULAR, 1) }
            assertTrue("Live chapter should have public comments", page.comments.isNotEmpty())
            val reactions = withContext(Dispatchers.IO) { provider.reactions() }
            assertTrue(reactions.any { it.count > 0 })
            android.util.Log.i("ChapterCommentsTest", "Shinigami GET verified: ${page.comments.size} comments, ${reactions.size} reaction types")
            instrumentation.runOnMainSync {
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previous).detectNetwork().penaltyDeath().build())
                try { session.close() } finally { StrictMode.setThreadPolicy(previous) }
            }
        } finally { session.close() }
    }
}
