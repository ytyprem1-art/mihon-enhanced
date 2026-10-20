package eu.kanade.tachiyomi.ui.mod.browselibrarystyle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class BrowseLibraryAppearance(
    val style: Int = 0,
    val outlineColor: Int = 0,
    val badgeStyle: Int = 0,
) {
    fun coverAlpha(inLibrary: Boolean): Float =
        if (inLibrary && style != 1) CommonMangaItemDefaults.BrowseFavoriteCoverAlpha else 1f
}

object BrowseLibraryPreferences {
    private val preferences by lazy { Injekt.get<PreferenceStore>() }
    val style by lazy { preferences.getInt("mod_browse_library_style", 0) }
    val outlineColor by lazy { preferences.getInt("mod_browse_library_outline_color", 0) }
    val badgeStyle by lazy { preferences.getInt("mod_browse_library_badge_style", 0) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val appearance by lazy {
        combine(style.changes(), outlineColor.changes(), badgeStyle.changes(), ::BrowseLibraryAppearance)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(5_000),
                BrowseLibraryAppearance(style.get(), outlineColor.get(), badgeStyle.get()),
            )
    }
}

@Composable
fun browseLibraryAppearance(): BrowseLibraryAppearance {
    val appearance by BrowseLibraryPreferences.appearance.collectAsStateWithLifecycle()
    return appearance
}

@Composable
private fun BrowseLibraryAppearance.color(): Color = when (outlineColor) {
    1 -> Color(0xFF1976D2)
    2 -> Color(0xFF69F06F)
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun browseLibraryOutline(inLibrary: Boolean, appearance: BrowseLibraryAppearance): Modifier =
    if (inLibrary && appearance.style == 1) {
        Modifier.border(3.dp, appearance.color(), MaterialTheme.shapes.extraSmall)
    } else {
        Modifier
    }

@Composable
fun BrowseLibraryBadge(enabled: Boolean, original: @Composable () -> Unit) {
    if (!enabled) return
    val appearance = browseLibraryAppearance()
    if (appearance.badgeStyle == 1) {
        Text(
            text = "In Library",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color = Color.White,
            modifier = Modifier.background(Color(0xFF70D96C), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    } else {
        original()
    }
}
