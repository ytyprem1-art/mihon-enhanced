package eu.kanade.tachiyomi.ui.mod.panelshare

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonAdapter
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer

/** Reader-specific metadata lives here so the upstream activity only needs one callback. */
fun PanelShareController.startReader(activity: ReaderActivity, hideMenus: () -> Unit) {
    val state = activity.viewModel.state.value
    val manga = state.manga ?: return
    val readerChapter = state.currentChapter ?: return
    val chapter = readerChapter.chapter
    val source = activity.viewModel.getSource() ?: return
    var pageIndex = (state.currentPage - 1).coerceAtLeast(0)
    var pageOffset = 0.0
    (state.viewer as? WebtoonViewer)?.recycler?.let { recycler ->
        val adapter = recycler.adapter as? WebtoonAdapter ?: return@let
        val visible = (0 until recycler.childCount).mapNotNull { index ->
            val child = recycler.getChildAt(index)
            val page = adapter.items.getOrNull(recycler.getChildAdapterPosition(child)) as? ReaderPage
            if (page?.chapter !== readerChapter) null else child to page
        }.maxByOrNull { (child, _) -> minOf(child.bottom, recycler.height) - maxOf(child.top, 0) }
        visible?.let { (child, page) ->
            pageIndex = page.index
            pageOffset = (-child.top.toDouble() / child.height.coerceAtLeast(1)).coerceIn(0.0, .99)
        }
    }
    start(
        activity,
        readerChapter,
        pageIndex,
        PanelContext(
            mangaTitle = manga.title,
            mangaUrl = manga.url,
            sourceId = manga.source,
            sourceName = source.name,
            sourceDomain = (source as? HttpSource)?.baseUrl?.let {
                runCatching { java.net.URI(it).host }.getOrNull()
            },
            chapterName = chapter.name,
            chapterUrl = chapter.url,
        ),
        hideMenus,
        pageOffset,
    )
}
