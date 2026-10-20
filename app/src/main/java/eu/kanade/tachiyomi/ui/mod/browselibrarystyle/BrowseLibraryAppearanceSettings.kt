package eu.kanade.tachiyomi.ui.mod.browselibrarystyle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import eu.kanade.presentation.more.settings.Preference
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun browseLibraryAppearanceSettings(): Preference.PreferenceGroup {
    val preferences = BrowseLibraryPreferences
    val style by preferences.style.collectAsState()
    return Preference.PreferenceGroup(
        title = "Library indicators in Browse",
        preferenceItems = listOf(
            Preference.PreferenceItem.ListPreference(
                preference = preferences.style,
                title = "Library cover appearance",
                entries = linkedMapOf(0 to "Default (dimmed)", 1 to "Bright cover with outline"),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.outlineColor,
                title = "Library outline color",
                entries = linkedMapOf(0 to "Follow app theme", 1 to "Blue", 2 to "Green"),
                visible = style == 1,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.badgeStyle,
                title = "Library badge",
                entries = linkedMapOf(0 to "Default icon", 1 to "In Library text"),
            ),
            Preference.PreferenceItem.InfoPreference(
                title = "Applies to source Browse, including Popular, Latest, and Latest Library. Personal favorite stars appear below the badge.",
            ),
        ),
    )
}
