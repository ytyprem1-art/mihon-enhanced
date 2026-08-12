package eu.kanade.tachiyomi.data.backup.restore

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

data class RestoreOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,

    // Mod data
    val modHistoryCategories: Boolean = true,
    val modHistoryGroups: Boolean = true,
    val modLinkedSources: Boolean = true,
    val modUpdateWatch: Boolean = true,
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        appSettings,
        extensionStores,
        sourceSettings,
        modHistoryCategories,
        modHistoryGroups,
        modLinkedSources,
        modUpdateWatch,
    )

    fun canRestore() = libraryEntries || categories || appSettings || extensionStores || sourceSettings ||
        modHistoryCategories || modHistoryGroups || modLinkedSources || modUpdateWatch

    companion object {
        val options = listOf(
            Entry(
                label = MR.strings.label_library,
                getter = RestoreOptions::libraryEntries,
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.categories,
                getter = RestoreOptions::categories,
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.app_settings,
                getter = RestoreOptions::appSettings,
                setter = { options, enabled -> options.copy(appSettings = enabled) },
            ),
            Entry(
                label = MR.strings.extensionStores,
                getter = RestoreOptions::extensionStores,
                setter = { options, enabled -> options.copy(extensionStores = enabled) },
            ),
            Entry(
                label = MR.strings.source_settings,
                getter = RestoreOptions::sourceSettings,
                setter = { options, enabled -> options.copy(sourceSettings = enabled) },
            ),
        )

        val modOptions = listOf(
            Entry(
                label = MR.strings.mod_history_categories,
                getter = RestoreOptions::modHistoryCategories,
                setter = { options, enabled -> options.copy(modHistoryCategories = enabled) },
            ),
            Entry(
                label = MR.strings.mod_history_groups,
                getter = RestoreOptions::modHistoryGroups,
                setter = { options, enabled -> options.copy(modHistoryGroups = enabled) },
            ),
            Entry(
                label = MR.strings.mod_linked_sources,
                getter = RestoreOptions::modLinkedSources,
                setter = { options, enabled -> options.copy(modLinkedSources = enabled) },
            ),
            Entry(
                label = MR.strings.mod_update_watch,
                getter = RestoreOptions::modUpdateWatch,
                setter = { options, enabled -> options.copy(modUpdateWatch = enabled) },
            ),
        )

        fun fromBooleanArray(array: BooleanArray) = RestoreOptions(
            libraryEntries = array[0],
            categories = array[1],
            appSettings = array[2],
            extensionStores = array[3],
            sourceSettings = array[4],
            modHistoryCategories = array.getOrElse(5) { true },
            modHistoryGroups = array.getOrElse(6) { true },
            modLinkedSources = array.getOrElse(7) { true },
            modUpdateWatch = array.getOrElse(8) { true },
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (RestoreOptions) -> Boolean,
        val setter: (RestoreOptions, Boolean) -> RestoreOptions,
    )
}
