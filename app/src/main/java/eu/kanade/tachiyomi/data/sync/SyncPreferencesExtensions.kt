package eu.kanade.tachiyomi.data.sync

import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupOptions

fun SyncPreferences.getCloudSyncOptions(): BackupOptions {
    val set = cloudSyncOptions().get()
    return BackupOptions(
        libraryEntries = "libraryEntries" in set,
        categories = "categories" in set,
        chapters = "chapters" in set,
        tracking = "tracking" in set,
        history = "history" in set,
        readEntries = "readEntries" in set,
        appSettings = "appSettings" in set,
        extensionStores = "extensionStores" in set,
        sourceSettings = "sourceSettings" in set,
        privateSettings = "privateSettings" in set,
        modHistoryCategories = "modHistoryCategories" in set,
        modHistoryGroups = "modHistoryGroups" in set,
        modLinkedSources = "modLinkedSources" in set,
        modUpdateWatch = "modUpdateWatch" in set,
    )
}

fun SyncPreferences.setCloudSyncOptions(options: BackupOptions) {
    val set = mutableSetOf<String>()
    if (options.libraryEntries) set.add("libraryEntries")
    if (options.categories) set.add("categories")
    if (options.chapters) set.add("chapters")
    if (options.tracking) set.add("tracking")
    if (options.history) set.add("history")
    if (options.readEntries) set.add("readEntries")
    if (options.appSettings) set.add("appSettings")
    if (options.extensionStores) set.add("extensionStores")
    if (options.sourceSettings) set.add("sourceSettings")
    if (options.privateSettings) set.add("privateSettings")
    if (options.modHistoryCategories) set.add("modHistoryCategories")
    if (options.modHistoryGroups) set.add("modHistoryGroups")
    if (options.modLinkedSources) set.add("modLinkedSources")
    if (options.modUpdateWatch) set.add("modUpdateWatch")
    cloudSyncOptions().set(set)
}
