package eu.kanade.tachiyomi.ui.mod.merge

import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SmartSearchScreenModel(
    sourceId: Long,
    private val title: String,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    sourceManager: SourceManager = Injekt.get(),
) : androidx.lifecycle.ViewModel() {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<SmartSearchScreenModel.SearchResults?>(null)
    val state: kotlinx.coroutines.flow.StateFlow<SmartSearchScreenModel.SearchResults?> = mutableState


    private val smartSearchEngine = SmartSourceSearchEngine(null)

    var source: eu.kanade.tachiyomi.source.Source? by androidx.compose.runtime.mutableStateOf(null)
        private set

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val source = sourceManager.getOrStub(sourceId)
            this@SmartSearchScreenModel.source = source
            val result = try {
                val resultManga = smartSearchEngine.deepSearch(source, title)
                if (resultManga != null) {
                    val localManga = networkToLocalManga(resultManga)
                    SearchResults.Found(localManga)
                } else {
                    SearchResults.NotFound
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                } else {
                    SearchResults.Error
                }
            }

            mutableState.update { result }
        }
    }

    sealed class SearchResults {
        data class Found(val manga: Manga) : SearchResults()
        data object NotFound : SearchResults()
        data object Error : SearchResults()
    }
}
