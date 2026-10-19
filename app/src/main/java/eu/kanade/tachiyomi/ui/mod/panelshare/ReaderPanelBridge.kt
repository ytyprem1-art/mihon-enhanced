package eu.kanade.tachiyomi.ui.mod.panelshare

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/** Reader-specific metadata lives here so the upstream activity only needs one callback. */
fun PanelShareController.startReader(activity: ReaderActivity, hideMenus: () -> Unit) {
    val state = activity.viewModel.state.value
    val manga = state.manga ?: return
    val chapter = state.currentChapter?.chapter ?: return
    val source = activity.viewModel.getSource() ?: return
    start(
        activity,
        activity.binding.viewerContainer,
        activity.binding.composeOverlay,
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
    )
}
