package eu.kanade.tachiyomi.ui.mod.chaptercomments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Owned by the popup, not a disposable LazyColumn row. Never persisted across popup sessions. */
internal class CommentThreadState(comment: ChapterComment) {
    var expanded by mutableStateOf(false)
    var visibleReplies by mutableIntStateOf(10)
    var replies by mutableStateOf(comment.replies)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var reveal by mutableStateOf(!commentBody(comment).spoiler)
}
