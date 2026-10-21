package eu.kanade.tachiyomi.ui.mod

import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.mod.chaptercomments.ChapterReactionsProvider
import eu.kanade.tachiyomi.ui.mod.chaptercomments.CommentSort
import eu.kanade.tachiyomi.ui.mod.chaptercomments.CommentTarget
import eu.kanade.tachiyomi.ui.mod.chaptercomments.CommentsSession
import eu.kanade.tachiyomi.ui.mod.chaptercomments.DoujindesuCommentsProvider
import eu.kanade.tachiyomi.ui.mod.chaptercomments.HtmlCommentsProvider
import eu.kanade.tachiyomi.ui.mod.chaptercomments.KomikuAsiaCommentsProvider
import eu.kanade.tachiyomi.ui.mod.chaptercomments.QqCommentsProvider
import eu.kanade.tachiyomi.ui.mod.chaptercomments.chapterCommentsProvider
import eu.kanade.tachiyomi.ui.mod.chaptercomments.resolveCommentTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real anonymous reads only. No votes, account logins, database changes or chapter image requests. */
class ChapterCommentsEnginesDeviceTest {
    @Test fun komikuAsiaPublicChapterAndInstalledSourceIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph: AppGraph = instrumentation.targetContext.metroGraph()
        val details = StringBuilder()
        val history = graph.enhancedHistoryRepository.getHistory("Nano").first()
        for (mangaId in history.map { it.mangaId }.distinct()) {
            val chapter = graph.enhancedChapterRepository.getChapterByMangaId(mangaId, false)
                .firstOrNull { it.chapterNumber == 334.0 } ?: continue
            val target = resolveCommentTarget(graph, chapter)
            if (!target.sourceName.contains("Komiku", true)) continue
            details.appendLine("Raw chapter: ${chapter.url}; resolved: $target")
            withContext(Dispatchers.IO) {
                java.io.File(
                    instrumentation.targetContext.cacheDir,
                    "comments-komiku-test.txt",
                ).writeText(details.toString())
            }
            session().use { transport ->
                val provider = chapterCommentsProvider(target, transport)
                val page = provider.comments(CommentSort.POPULAR, 1)
                assertTrue(page.comments.isNotEmpty())
                details.appendLine("Installed source: ${page.total} comments; ${provider.reactions()}")
            }
        }
        session().use { transport ->
            val provider =
                chapterCommentsProvider(
                    CommentTarget("https://01.komiku.asia/read/id/nano-machine/ch334-517833", "334", "Komiku.com"),
                    transport,
                )
            assertTrue(provider is KomikuAsiaCommentsProvider)
            assertFalse(provider is ChapterReactionsProvider)
            val page = provider.comments(CommentSort.POPULAR, 1)
            assertTrue(page.comments.isNotEmpty())
            assertTrue(page.comments.any { it.replies.isNotEmpty() })
            assertEquals(6, provider.reactions().size)
            details.appendLine(
                "Canonical chapter: ${page.total} comments; six reaction counts, login required for writes",
            )
        }
        withContext(Dispatchers.IO) {
            java.io.File(
                instrumentation.targetContext.cacheDir,
                "comments-komiku-test.txt",
            ).writeText(details.toString())
        }
    }

    @Test fun mangabatsPublicQqEngineAndKiryuuReactionDiscoveryOnly() = runBlocking {
        session().use { transport ->
            val bats = withContext(Dispatchers.IO) {
                chapterCommentsProvider(
                    CommentTarget(
                        "https://www.mangabats.com/manga/nano-machine/chapter-327",
                        "327",
                        "Mangabats",
                    ),
                    transport,
                ).prepare()
            }
            assertTrue(bats is QqCommentsProvider)
            val comments = withContext(Dispatchers.IO) { bats.comments(CommentSort.NEWEST, 1) }
            assertTrue(comments.comments.isNotEmpty())
            val kiryuu = withContext(Dispatchers.IO) {
                chapterCommentsProvider(
                    CommentTarget(
                        "https://v7.kiryuu.to/manga/evolution-begins-with-a-big-tree/chapter-539.819915/",
                        "539",
                        "Kiryuu",
                    ),
                    transport,
                ).prepare()
            }
            assertTrue(kiryuu is ChapterReactionsProvider)
            val options = (kiryuu as ChapterReactionsProvider).reactionOptions
            assertTrue(options.any { it.countLabel == "Love" })
            assertTrue(options.any { it.countLabel == "Upvote" })
            assertEquals(6, options.size)
            // No votes sent: only metadata, comments and count reads.
            assertTrue(withContext(Dispatchers.IO) { kiryuu.reactions() }.isNotEmpty())
        }
    }

    private fun session(): CommentsSession {
        val graph: AppGraph = InstrumentationRegistry.getInstrumentation().targetContext.metroGraph()
        return CommentsSession(graph.enhancedNetworkHelper.client)
    }

    @Test fun manhwa18Chapter25UsesGenericServerTemplate() = runBlocking {
        session().use { transport ->
            val provider = withContext(Dispatchers.IO) {
                chapterCommentsProvider(
                    CommentTarget("https://manhwa18.cc/webtoon/the-meek-cat/chapter-25", "Chapter25", "Manhwa18.cc"),
                    transport,
                ).prepare()
            }
            assertTrue(provider is HtmlCommentsProvider)
            assertFalse(CommentSort.POPULAR in provider.supportedSorts)
            val page = withContext(Dispatchers.IO) { provider.comments(CommentSort.OLDEST, 1) }
            assertTrue(page.total >= page.comments.size)
        }
    }

    @Test fun doujinReaderHtmlAndAnonymousEncryptedComments() = runBlocking {
        session().use { transport ->
            val target =
                CommentTarget(
                    "https://doujin.desu.xxx/reader/b19a6f3a-bf85-4711-a6e3-8e415fbe79ec",
                    "Chapter65",
                    "Doujindesu",
                )
            val html = transport.get(target.url)
            assertTrue("Reader route must receive HTML Accept header", DoujindesuCommentsProvider.recognizes(html))
            val provider = chapterCommentsProvider(target, transport).prepare()
            assertFalse(provider is ChapterReactionsProvider) // Website requires login for writes.
            val page = withContext(Dispatchers.IO) { provider.comments(CommentSort.NEWEST, 1) }
            assertTrue(page.comments.isNotEmpty())
            assertTrue(page.total >= page.comments.size)
            assertTrue(provider.reactions().isNotEmpty())
        }
    }

    @Test fun mangaDexUserChapterResolvesExactPublicForumThread() = runBlocking {
        session().use { transport ->
            val target =
                CommentTarget(
                    "https://mangadex.org/chapter/71e67c07-0d9b-4d2b-bcf7-212f1db2ea66/2",
                    "Chapter9",
                    "MangaDex",
                )
            val provider = withContext(Dispatchers.IO) { chapterCommentsProvider(target, transport).prepare() }
            assertEquals("https://forums.mangadex.org/threads/2748720", provider.discussionUrl)
            assertEquals(listOf(CommentSort.OLDEST), provider.supportedSorts)
            val page = withContext(Dispatchers.IO) { provider.comments(CommentSort.OLDEST, 1) }
            assertTrue(page.comments.isNotEmpty())
            assertFalse(page.comments.any { it.author == "MangaDex" })
            assertFalse(page.comments.any { it.voteCountsAvailable })
        }
    }
}
