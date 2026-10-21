package eu.kanade.tachiyomi.ui.mod.personalfavorites

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One shared index for all covers: no database lookup per visible grid item. */
@OptIn(ExperimentalCoroutinesApi::class)
object PersonalFavorites {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store by lazy { PersonalFavoriteStore(Injekt.get<PreferenceStore>()) }
    val marks by lazy {
        store.changes().stateIn(scope, SharingStarted.Eagerly, store.snapshot())
    }
    val coverIds by lazy {
        val repository = Injekt.get<MangaRepository>()
        marks.flatMapLatest { favorites ->
            if (favorites.isEmpty()) {
                flowOf(emptySet())
            } else {
                combine(
                    favorites.map { identity ->
                        repository.getMangaByUrlAndSourceIdAsFlow(identity.url, identity.source)
                            .map { manga -> manga?.let { it.source to it.id } }
                    },
                ) { matches -> matches.filterNotNull().toSet() }
            }
        }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptySet())
    }
}
