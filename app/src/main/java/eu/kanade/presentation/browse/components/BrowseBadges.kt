package eu.kanade.presentation.browse.components

import eu.kanade.tachiyomi.ui.mod.browselibrarystyle.BrowseLibraryBadge

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.runtime.Composable
import tachiyomi.presentation.core.components.Badge

@Composable
internal fun InLibraryBadge(enabled: Boolean) {
    BrowseLibraryBadge(enabled) {
        Badge(
            imageVector = Icons.Outlined.CollectionsBookmark,
        )
    }
}
