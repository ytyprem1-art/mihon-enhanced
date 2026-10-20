package eu.kanade.tachiyomi.ui.mod.search

import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.SmartSearchMerge
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors

class GlobalSourceSearchScreenModel(
    initialQuery: String? = null,
    parentMangaId: Long? = null,
    private val sourceManager: SourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getMangaInteractor: GetManga = Injekt.get(),
    private val getMergedReferencesById: GetMergedReferencesById = Injekt.get(),
    private val smartSearchMerge: SmartSearchMerge = Injekt.get(),
) : androidx.lifecycle.ViewModel() {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<GlobalSourceSearchScreenModel.State>(State(searchQuery = initialQuery))
    val state: kotlinx.coroutines.flow.StateFlow<GlobalSourceSearchScreenModel.State> = mutableState


    val snackbarHostState = SnackbarHostState()
    private val activeParentIdState = MutableStateFlow(parentMangaId)

    private val coroutineDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher()
    private var searchJob: Job? = null

    init {
        if (!initialQuery.isNullOrBlank()) {
            search()
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            activeParentIdState
                .filterNotNull()
                .flatMapLatest { id ->
                    getMergedReferencesById.subscribe(id)
                }
                .collectLatest { refs ->
                    val mergedIds = refs.mapNotNull { it.mangaId }.toSet()
                    mutableState.update { it.copy(mergedMangaIds = mergedIds) }
                }
        }
    }

    @Composable
    fun getManga(initialManga: Manga): androidx.compose.runtime.State<Manga> {
        return produceState(initialValue = initialManga) {
            getMangaInteractor.subscribe(initialManga.url, initialManga.source)
                .filterNotNull()
                .collectLatest { manga ->
                    value = manga
                }
        }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun showMergeConfirmation(manga: Manga) {
        mutableState.update { it.copy(dialog = Dialog.MergeConfirmation(manga)) }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun performMerge(
        manga: Manga,
        onFirstMergeCreated: ((oldParentId: Long, newMergedMangaId: Long) -> Unit)? = null,
    ) {
        val parentId = activeParentIdState.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val resultManga = smartSearchMerge.await(manga, parentId)
                val previousParentId = activeParentIdState.value
                activeParentIdState.value = resultManga.id
                dismissDialog()
                if (previousParentId != null && resultManga.id != previousParentId) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onFirstMergeCreated?.invoke(previousParentId, resultManga.id)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                dismissDialog()
                snackbarHostState.showSnackbar(
                    message = e.message ?: "Failed to merge manga",
                )
            }
        }
    }

    fun search() {
        val query = state.value.searchQuery
        if (query.isNullOrBlank()) return

        searchJob?.cancel()

        val disabledSources = sourcePreferences.disabledSources.get()
        searchJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val sources = sourceManager.getAll()
            .filterIsInstance<CatalogueSource>()
            .filter { "${it.id}" !in disabledSources }
            .distinctBy { it.id }
            .sortedBy { it.name }

        mutableState.update {
            it.copy(
                items = sources.associateWith { SearchItemResult.Loading },
            )
        }

            sources.map { source ->
                async {
                    try {
                        val page = withContext(coroutineDispatcher) {
                            source.getSearchManga(1, query, source.getFilterList())
                        }

                        val titles = page.mangas
                            .map { it.toDomainManga(source.id) }
                            .distinctBy { it.url }
                            .let { networkToLocalManga(it) }

                        if (isActive) {
                            updateItem(source, SearchItemResult.Success(titles))
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun updateItem(source: CatalogueSource, result: SearchItemResult) {
        mutableState.update { state ->
            val newItems = state.items.toMutableMap()
            newItems[source] = result
            state.copy(items = newItems)
        }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val items: Map<CatalogueSource, SearchItemResult> = emptyMap(),
        val mergedMangaIds: Set<Long> = emptySet(),
        val dialog: Dialog? = null,
    ) {
        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) ->
            result is SearchItemResult.Success && !result.isEmpty
        }
    }

    sealed interface Dialog {
        data class MergeConfirmation(val manga: Manga) : Dialog
    }

    sealed interface SearchItemResult {
        data object Loading : SearchItemResult
        data class Error(val throwable: Throwable) : SearchItemResult
        data class Success(val result: List<Manga>) : SearchItemResult {
            val isEmpty: Boolean get() = result.isEmpty()
        }
    }
}
