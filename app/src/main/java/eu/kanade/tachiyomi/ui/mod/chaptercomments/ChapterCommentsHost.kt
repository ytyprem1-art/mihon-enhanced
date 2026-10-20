package eu.kanade.tachiyomi.ui.mod.chaptercomments

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.ui.mod.di.EnhancedGraph
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import java.text.DateFormat
import java.util.Date
import okhttp3.HttpUrl.Companion.toHttpUrl

class ChapterCommentsController {
    var chapter by mutableStateOf<Chapter?>(null)
        private set
    internal var release: (() -> Unit)? = null

    fun open(chapter: Chapter) { close(); this.chapter = chapter }
    fun close() { release?.invoke(); release = null; chapter = null }
}

@Composable
fun ChapterCommentsHost(controller: ChapterCommentsController, currentChapter: Chapter?, graph: EnhancedGraph) {
    val requested = controller.chapter ?: return
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, requested.id) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.close() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(currentChapter?.id) {
        if (currentChapter?.id != requested.id) controller.close()
    }
    key(requested.id) { CommentsPopup(controller, requested, graph) }
}

@Composable
private fun CommentsPopup(controller: ChapterCommentsController, chapter: Chapter, graph: EnhancedGraph) {
    val context = LocalContext.current
    val transport = remember { CommentsSession(graph.enhancedNetworkHelper.client) }
    val media = remember { CommentImages(context.applicationContext, transport.client) }
    val images = media.loader
    DisposableEffect(Unit) {
        val close = { transport.close(); media.close() }
        controller.release = close
        onDispose { close(); controller.release = null }
    }
    var target by remember { mutableStateOf<CommentTarget?>(null) }
    var provider by remember { mutableStateOf<ChapterCommentsProvider?>(null) }
    var sort by remember { mutableStateOf(CommentSort.POPULAR) }
    var retry by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var comments by remember { mutableStateOf(emptyList<ChapterComment>()) }
    var reactions by remember { mutableStateOf(emptyList<ChapterReaction>()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reactionError by remember { mutableStateOf(false) }
    val savedVotes = remember { ChapterReactionSelections(graph.enhancedPreferenceStore) }
    var voteKey by remember { mutableStateOf<String?>(null) }
    var selectedReaction by remember { mutableStateOf<String?>(null) }
    var voteBusy by remember { mutableStateOf(false) }
    var voteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val threadStates = remember(provider) { mutableMapOf<String, CommentThreadState>() }

    LaunchedEffect(retry) {
        if (provider != null) return@LaunchedEffect
        loading = true; error = null
        try {
            val resolved = resolveCommentTarget(graph, chapter)
            target = resolved
            val active = withContext(Dispatchers.IO) { chapterCommentsProvider(resolved, transport).prepare() }
            if (sort !in active.supportedSorts) sort = active.supportedSorts.first()
            provider = active
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = e.message ?: "Tidak bisa membuka komentar."; loading = false }
    }
    LaunchedEffect(provider, sort, retry) {
        val active = provider ?: return@LaunchedEffect
        loading = true; error = null; page = 1; comments = emptyList(); total = null; hasMore = false
        supervisorScope {
            val reactionJob = async(Dispatchers.IO) {
                try { active.reactions() } catch (e: CancellationException) { throw e
                } catch (_: Exception) { null }
            }
            try {
                val result = withContext(Dispatchers.IO) { active.comments(sort, 1) }
                comments = result.comments.distinctBy { it.id }; total = result.total.takeIf { result.totalKnown }; hasMore = result.hasMore
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                android.util.Log.w("ChapterComments", "Unable to read comments for ${target?.sourceName}", e)
                error = e.message?.takeIf { it.isNotBlank() } ?: "Komentar gagal dimuat (${e.javaClass.simpleName})."
            } finally { loading = false }
            val counts = reactionJob.await()
            reactionError = counts == null
            reactions = counts.orEmpty()
        }
    }

    LaunchedEffect(provider, retry) {
        val voting = provider as? ChapterReactionsProvider ?: return@LaunchedEffect
        try {
            val identity = withContext(Dispatchers.IO) { voting.chapterVoteIdentity() }
            voteKey = identity
            selectedReaction = savedVotes.get(identity, voting.reactionOptions)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { /* The read path reports identity failures and allows retry. */ }
    }

    Dialog(onDismissRequest = controller::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(.94f).widthIn(max = 840.dp).fillMaxHeight(.86f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Komentar", style = MaterialTheme.typography.titleLarge)
                        Text("${target?.sourceName ?: ""} · ${chapter.name}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    IconButton(onClick = controller::close) { Icon(Icons.Outlined.Close, "Tutup komentar") }
                }
                val voting = provider as? ChapterReactionsProvider
                if (voting != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        voting.reactionOptions.forEach { option ->
                            val count = reactions.firstOrNull { it.label == option.countLabel }?.count
                            FilterChip(
                                selected = selectedReaction == option.key,
                                enabled = !voteBusy && voteKey != null,
                                label = { Text(count?.toString() ?: "…") },
                                leadingIcon = {
                                    if (option.imageUrl != null) {
                                        AsyncImage(model = option.imageUrl, imageLoader = images, contentDescription = option.countLabel, modifier = Modifier.size(30.dp))
                                    } else Text(option.emoji, style = MaterialTheme.typography.titleMedium)
                                },
                                onClick = {
                                    if (voteBusy) return@FilterChip
                                    voteBusy = true; voteError = null
                                    val previous = selectedReaction
                                    val next = option.key.takeUnless { it == previous }
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { voting.setChapterReaction(previous, next) }
                                            voteKey?.let { savedVotes.set(it, next) }
                                            selectedReaction = next
                                        } catch (e: CancellationException) { throw e
                                        } catch (e: Exception) {
                                            if (e is ChapterReactionUpdateException) {
                                                selectedReaction = e.confirmedSelection
                                                voteKey?.let { savedVotes.set(it, e.confirmedSelection) }
                                            }
                                            voteError = e.message ?: "Reaksi belum berhasil dikirim."
                                        }
                                        try {
                                            reactions = withContext(Dispatchers.IO) { provider?.reactions().orEmpty() }
                                            reactionError = false
                                        } catch (e: CancellationException) { throw e
                                        } catch (_: Exception) { reactionError = true
                                        } finally { voteBusy = false }
                                    }
                                },
                            )
                        }
                    }
                    if (voteBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    voteError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                } else if (reactions.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        reactions.forEach { Text("${it.label} ${it.count}", style = MaterialTheme.typography.labelMedium) }
                    }
                } else if (reactionError) { Text("Jumlah reaksi belum tersedia.", style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (provider?.supportedSorts ?: CommentSort.entries).forEach { value ->
                        FilterChip(selected = value == sort, onClick = { if (!loading) sort = value }, label = { Text(value.label) })
                    }
                }
                HorizontalDivider()
                if (comments.isNotEmpty()) {
                    val knownTotal = total?.takeIf { it >= comments.size }
                    Text(
                        if (knownTotal != null) "${comments.size} komentar dimuat dari $knownTotal komentar"
                        else "${comments.size} komentar dimuat · total belum tersedia",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(comments, key = { it.id }) { comment ->
                        key(sort) { CommentItem(comment, images, provider, threadStates, scope) }
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                    }
                    if (loading) item { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp)); Text(if (comments.isEmpty()) "Menghubungkan…" else "Memuat…")
                    } }
                    error?.let { message -> item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retry++ }) { Text("Coba lagi") }
                    } }
                    if (!loading && error == null && provider != null && comments.isEmpty()) item { Text("Belum ada komentar di chapter ini.") }
                    if (hasMore && !loading && error == null) item {
                        TextButton(onClick = {
                            val active = provider ?: return@TextButton
                            scope.launch {
                                loading = true
                                try {
                                    val result = withContext(Dispatchers.IO) { active.comments(sort, page + 1) }
                                    comments = (comments + result.comments).distinctBy { it.id }; page++
                                    if (result.totalKnown) total = result.total
                                    hasMore = result.hasMore
                                } catch (e: CancellationException) { throw e
                                } catch (e: Exception) { error = e.message ?: "Komentar gagal dimuat."
                                } finally { loading = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Muat komentar berikutnya") }
                    }
                }
                target?.let { resolved ->
                    TextButton(onClick = {
                        controller.close()
                        val url = provider?.discussionUrl ?: resolved.url
                        val sourceId = resolved.sourceId.takeIf { url.toHttpUrl().host == resolved.url.toHttpUrl().host }
                        context.startActivity(WebViewActivity.newIntent(context, url, sourceId, resolved.sourceName))
                    }, modifier = Modifier.align(Alignment.End)) { Text("Buka web") }
                }
            }
        }
    }
}

@Composable
private fun CommentItem(
    comment: ChapterComment,
    images: ImageLoader,
    provider: ChapterCommentsProvider?,
    threadStates: MutableMap<String, CommentThreadState>,
    popupScope: CoroutineScope,
    isReply: Boolean = false,
) {
    val body = remember(comment) { commentBody(comment) }
    val state = threadStates.getOrPut(comment.id) { CommentThreadState(comment) }
    var reveal by state::reveal
    var expanded by state::expanded
    var visibleReplies by state::visibleReplies
    var replies by state::replies
    var loading by state::loading
    var error by state::error
    var previewImage by remember { mutableStateOf<String?>(null) }
    previewImage?.let { url -> CommentImagePreview(url, images) { previewImage = null } }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            comment.avatar?.let { url ->
                AsyncImage(model = url, imageLoader = images, contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Text(comment.author, style = MaterialTheme.typography.labelLarge)
                val time = remember(comment.createdAt) { commentTime(comment.createdAt) }
                if (time.isNotBlank()) Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (comment.voteCountsAvailable && comment.reactionCounts.isEmpty()) Text("↑ ${comment.upvotes}" + if (comment.downvotes > 0) "  ↓ ${comment.downvotes}" else "", style = MaterialTheme.typography.labelSmall)
        }
        if (comment.reactionCounts.isNotEmpty()) Text(comment.reactionCounts.joinToString("  ") { "${it.label} ${it.count}" }, style = MaterialTheme.typography.labelSmall)
        if (!reveal) {
            TextButton(onClick = { reveal = true }) { Text("Tampilkan spoiler") }
        } else {
            comment.replyTo?.let { Text("↳ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            if (body.text.isNotBlank()) SelectionContainer { Text(body.text, style = MaterialTheme.typography.bodyMedium) }
            body.media.forEach { url ->
                val context = LocalContext.current
                val request = remember(url) { ImageRequest.Builder(context).data(url).size(640, 640)
                    .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).build() }
                var mediaLoading by remember(url) { mutableStateOf(true) }
                var mediaError by remember(url) { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth().height(180.dp).clickable { previewImage = url }, contentAlignment = Alignment.Center) {
                    AsyncImage(model = request, imageLoader = images, contentDescription = "Gambar komentar", contentScale = ContentScale.Fit,
                        onLoading = { mediaLoading = true; mediaError = false },
                        onSuccess = { mediaLoading = false; mediaError = false },
                        onError = { mediaLoading = false; mediaError = true },
                        modifier = Modifier.fillMaxSize())
                    if (mediaLoading) CircularProgressIndicator(Modifier.size(24.dp))
                    if (mediaError) Text("Gambar gagal dimuat. Ketuk untuk membuka.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!isReply && (comment.replyCount > 0 || replies.isNotEmpty())) {
            TextButton(enabled = !loading, onClick = {
                if (expanded) { expanded = false } else popupScope.launch {
                    loading = true; error = null
                    try {
                        if (replies.size < comment.replyCount) replies = withContext(Dispatchers.IO) { provider?.replies(comment).orEmpty() }
                        expanded = true
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { error = e.message ?: "Balasan gagal dimuat."
                    } finally { loading = false }
                }
            }) { Text(if (loading) "Memuat balasan…" else if (expanded) "Tutup balasan" else "${maxOf(comment.replyCount, replies.size)} balasan") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val threadLine = MaterialTheme.colorScheme.primary.copy(alpha = .35f)
            if (expanded) Column(Modifier.padding(start = 24.dp).drawBehind {
                drawLine(threadLine, Offset.Zero, Offset(0f, size.height), strokeWidth = 2.dp.toPx())
            }.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val thread = remember(replies) { flattenCommentReplies(replies, comment.author) }
                thread.take(visibleReplies).forEach { reply ->
                    key(reply.id) {
                        HorizontalDivider()
                        CommentItem(reply, images, provider, threadStates, popupScope, isReply = true)
                    }
                }
                if (visibleReplies < thread.size) TextButton(onClick = { visibleReplies += 10 }) { Text("Lihat balasan berikutnya") }
            }
        }
    }
}

private fun commentTime(value: String): String = runCatching {
    val millis = value.toLongOrNull() ?: java.time.Instant.parse(value).toEpochMilli()
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}.getOrDefault(value.take(40))
