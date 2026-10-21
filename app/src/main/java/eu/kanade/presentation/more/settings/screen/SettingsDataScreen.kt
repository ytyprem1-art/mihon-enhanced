package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.more.settings.screen.data.StorageInfo
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.backup.create.BackupCreateWorker
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.export.LibraryExporter
import eu.kanade.tachiyomi.data.export.LibraryExporter.ExportOptions
import eu.kanade.tachiyomi.data.sync.SyncManager
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.data.sync.service.SyncJob
import eu.kanade.tachiyomi.ui.mod.bookmarkimport.BookmarkImportScreen
import eu.kanade.tachiyomi.ui.mod.util.launchNonCancellable
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Help
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

object SettingsDataScreen : SearchableSettings {

    val restorePreferenceKeyString = MR.strings.label_backup
    const val HELP_URL = "https://mihon.app/docs/faq/storage"

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_data_storage

    @Composable
    override fun Content() {
        val handleBack = eu.kanade.presentation.util.LocalBackPress.current
        eu.kanade.presentation.more.settings.PreferenceScaffold(
            titleRes = getTitleRes(),
            onBackPressed = if (handleBack != null) handleBack::invoke else null,
            actions = { AppBarAction() },
            itemsProvider = { getPreferences() },
        )
    }

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val backupPreferences = remember { context.appGraph.backupPreferences }
        val storagePreferences = remember { context.appGraph.storagePreferences }
        val syncPreferences = Injekt.get<SyncPreferences>()

        return listOf(
            getStorageLocationPref(storagePreferences = storagePreferences),
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_storage_location_info)),

            getBackupAndRestoreGroup(backupPreferences = backupPreferences),
            getSyncGroup(syncPreferences = syncPreferences),
            getImportGroup(),
            getDataGroup(),
            getExportGroup(),
        )
    }

    @Composable
    private fun getSyncGroup(
        syncPreferences: SyncPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val googleDriveService = remember { Injekt.get<GoogleDriveService>() }
        val syncManager = remember { Injekt.get<SyncManager>() }
        val googleDriveSyncService = remember { Injekt.get<GoogleDriveSyncService>() }

        val googleDriveEmail by syncPreferences.googleDriveEmail().collectAsState()
        val lastSync by syncPreferences.lastSyncTimestamp().collectAsState()

        var showSignOutDialog by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }
        var isSyncing by remember { mutableStateOf(false) }

        if (showSignOutDialog) {
            AlertDialog(
                onDismissRequest = { showSignOutDialog = false },
                title = { Text("Sign out of Google Drive?") },
                text = { Text("Mihon will no longer be able to sync your data with Google Drive.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            googleDriveService.signOut()
                            Log.d("SyncServiceDebug", "UI: Sign out clicked. SETTING NONE.")
                            syncPreferences.syncService().set(SyncPreferences.SYNC_SERVICE_NONE)
                            showSignOutDialog = false
                        },
                    ) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutDialog = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete sync data from Google Drive?") },
                text = {
                    Text(
                        "This will delete all your Mihon sync data from Google Drive. Your local data will not be affected.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val result = googleDriveSyncService.deleteSyncDataFromGoogleDrive()
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    when (result) {
                                        GoogleDriveSyncService.DeleteSyncDataStatus.SUCCESS -> context.toast(
                                            "Sync data deleted successfully",
                                        )
                                        GoogleDriveSyncService.DeleteSyncDataStatus.NO_FILES -> context.toast(
                                            "No sync data found on Google Drive",
                                        )
                                        else -> context.toast("Failed to delete sync data")
                                    }
                                }
                            }
                            showDeleteDialog = false
                        },
                    ) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return Preference.PreferenceGroup(
            title = "Cloud Sync",
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = "Google Drive",
                    subtitle = googleDriveEmail.takeIf { it.isNotBlank() } ?: "Not signed in to Google Drive",
                    onClick = {
                        if (googleDriveService.isSignedIn()) {
                            showSignOutDialog = true
                        } else {
                            try {
                                val intent = googleDriveService.getSignInIntent()
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                context.toast(e.message)
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = "Enable cloud sync",
                ) {
                    val enabled by syncPreferences.cloudSyncEnabled().collectAsState()
                    SwitchPreferenceWidget(
                        title = "Enable cloud sync",
                        checked = enabled,
                        onCheckedChanged = { newValue ->
                            val before = syncPreferences.syncService().get()
                            Log.d(
                                "SyncServiceDebug",
                                "UI: Enable cloud sync toggle: $newValue. Current service value=$before",
                            )
                            scope.launch {
                                syncPreferences.cloudSyncEnabled().set(newValue)
                                if (newValue) {
                                    Log.d("SyncServiceDebug", "UI: SETTING GOOGLE DRIVE")
                                    syncPreferences.syncService().set(SyncPreferences.SYNC_SERVICE_GOOGLE_DRIVE)
                                    val after = syncPreferences.syncService().get()
                                    Log.d("SyncServiceDebug", "UI: Value immediately after set=$after")
                                    SyncJob.setupTask(context)
                                } else {
                                    Log.d("SyncServiceDebug", "UI: SETTING NONE")
                                    syncPreferences.syncService().set(SyncPreferences.SYNC_SERVICE_NONE)
                                    val after = syncPreferences.syncService().get()
                                    Log.d("SyncServiceDebug", "UI: Value immediately after set=$after")
                                    SyncJob.setupTask(context, 0)
                                }
                            }
                        },
                        enabled = googleDriveEmail.isNotBlank(),
                    )
                },
                Preference.PreferenceItem.ListPreference(
                    preference = syncPreferences.syncInterval(),
                    entries = mapOf(
                        0 to stringResource(MR.strings.off),
                        1 to "Every hour",
                        2 to "Every 2 hours",
                        6 to "Every 6 hours",
                        12 to "Every 12 hours",
                        24 to "Daily",
                        48 to "Every 2 days",
                        168 to "Weekly",
                    ),
                    title = "Sync interval",
                    onValueChanged = {
                        SyncJob.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = syncPreferences.cloudSyncOptions(),
                    entries = mapOf(
                        "libraryEntries" to "Library entries",
                        "categories" to "Categories",
                        "chapters" to "Chapters",
                        "tracking" to "Tracking",
                        "history" to "History",
                        "readEntries" to "Read entries",
                        "appSettings" to "App settings",
                        "extensionStores" to "Extension stores",
                        "sourceSettings" to "Source settings",
                        "privateSettings" to "Private settings",
                        "modHistoryCategories" to "History categories",
                        "modHistoryGroups" to "History groups",
                        "modLinkedSources" to "Linked sources",
                        "modUpdateWatch" to "Update watch",
                    ),
                    title = "Sync settings",
                    subtitle = "Choose what data to sync",
                ),
                Preference.PreferenceItem.CustomPreference(
                    title = "Sync now",
                ) {
                    val isCloudSyncEnabled by syncPreferences.cloudSyncEnabled().collectAsState()
                    val isGoogleDriveSignedIn = googleDriveEmail.isNotBlank()
                    val canSync = !isSyncing && isGoogleDriveSignedIn && isCloudSyncEnabled
                    TextPreferenceWidget(
                        title = "Sync now",
                        subtitle = if (isSyncing) {
                            "Syncing..."
                        } else if (lastSync > 0L) {
                            "Last sync: ${relativeTimeSpanString(lastSync)}"
                        } else {
                            "Never"
                        },
                        onPreferenceClick = {
                            scope.launch {
                                isSyncing = true
                                try {
                                    syncManager.syncData(showUserNotification = true, rethrowErrors = true)
                                } catch (e: Exception) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) { context.toast(e.message) }
                                } finally {
                                    isSyncing = false
                                }
                            }
                        },
                        enabled = canSync,
                    )
                },
                Preference.PreferenceItem.CustomPreference(
                    title = "Delete sync data from Google Drive",
                ) {
                    TextPreferenceWidget(
                        title = "Delete sync data from Google Drive",
                        onPreferenceClick = { showDeleteDialog = true },
                        enabled = googleDriveEmail.isNotBlank(),
                    )
                },
            ),
        )
    }

    @Composable
    fun storageLocationPicker(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): ManagedActivityResultLauncher<Uri?, Uri?> {
        val context = LocalContext.current

        return rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                // For some reason InkBook devices do not implement the SAF properly. Persistable URI grants do not
                // work. However, simply retrieving the URI and using it works fine for these devices. Access is not
                // revoked after the app is closed or the device is restarted.
                // This also holds for some Samsung devices. Thus, we simply execute inside of a try-catch block and
                // ignore the exception if it is thrown.
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: SecurityException) {
                    logcat(LogPriority.ERROR, e)
                    context.toast(MR.strings.file_picker_uri_permission_unsupported)
                }

                UniFile.fromUri(context, uri)?.let {
                    storageDirPref.set(it.uri.toString())
                }
            }
        }
    }

    @Composable
    fun storageLocationText(
        storageDirPref: tachiyomi.core.common.preference.Preference<String>,
    ): String {
        val context = LocalContext.current
        val storageDir by storageDirPref.collectAsState()

        if (storageDir == storageDirPref.defaultValue()) {
            return stringResource(MR.strings.no_location_set)
        }

        return remember(storageDir) {
            val file = UniFile.fromUri(context, storageDir.toUri())
            file?.displayablePath
        } ?: stringResource(MR.strings.invalid_location, storageDir)
    }

    @Composable
    private fun getStorageLocationPref(
        storagePreferences: StoragePreferences,
    ): Preference.PreferenceItem.TextPreference {
        val context = LocalContext.current
        val pickStorageLocation = storageLocationPicker(storagePreferences.baseStorageDirectory)

        return Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_storage_location),
            subtitle = storageLocationText(storagePreferences.baseStorageDirectory),
            onClick = {
                try {
                    pickStorageLocation.launch(null)
                } catch (e: ActivityNotFoundException) {
                    context.toast(MR.strings.file_picker_error)
                }
            },
        )
    }

    @Composable
    private fun getBackupAndRestoreGroup(backupPreferences: BackupPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        val lastAutoBackup by backupPreferences.lastAutoBackupTimestamp.collectAsState()

        val chooseBackup = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) {
            if (it == null) {
                context.toast(MR.strings.file_null_uri_error)
                return@rememberLauncherForActivityResult
            }

            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                logcat(LogPriority.ERROR, e)
            }

            navigator.push(RestoreBackupScreen(it.toString()))
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_backup),
            preferenceItems = listOf(
                // Manual actions
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(restorePreferenceKeyString),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            MultiChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(intrinsicSize = IntrinsicSize.Min)
                                    .padding(horizontal = PrefsHorizontalPadding),
                            ) {
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = { navigator.push(CreateBackupScreen()) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_create_backup))
                                }
                                SegmentedButton(
                                    modifier = Modifier.fillMaxHeight(),
                                    checked = false,
                                    onCheckedChange = {
                                        scope.launch {
                                            if (!BackupRestoreWorker.isRunning(context.workManager)) {
                                                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                                                    context.toast(MR.strings.restore_miui_warning)
                                                }
                                                chooseBackup.launch(arrayOf("*/*"))
                                            } else {
                                                context.toast(MR.strings.restore_in_progress)
                                            }
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) {
                                    Text(stringResource(MR.strings.pref_restore_backup))
                                }
                            }
                        },
                    )
                },

                // Automatic backups
                Preference.PreferenceItem.ListPreference(
                    preference = backupPreferences.backupInterval,
                    entries = mapOf(
                        0 to stringResource(MR.strings.off),
                        6 to stringResource(MR.strings.update_6hour),
                        12 to stringResource(MR.strings.update_12hour),
                        24 to stringResource(MR.strings.update_24hour),
                        48 to stringResource(MR.strings.update_48hour),
                        168 to stringResource(MR.strings.update_weekly),
                    ),
                    title = stringResource(MR.strings.pref_backup_interval),
                    onValueChanged = {
                        BackupCreateWorker.setupTask(context, it)
                        true
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    stringResource(MR.strings.backup_info) + "\n\n" +
                        stringResource(MR.strings.last_auto_backup_info, relativeTimeSpanString(lastAutoBackup)),
                ),
            ),
        )
    }

    @Composable
    private fun getImportGroup(): Preference.PreferenceGroup {
        val navigator = LocalNavigator.currentOrThrow

        return Preference.PreferenceGroup(
            title = "Import",
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = "External bookmark import",
                    subtitle = "Import manga bookmarks from supported websites",
                    onClick = {
                        navigator.push(BookmarkImportScreen())
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val libraryPreferences = remember { context.appGraph.libraryPreferences }

        val chapterCache = remember { context.appGraph.chapterCache }
        var cacheReadableSizeSema by remember { mutableIntStateOf(0) }
        val cacheReadableSize by produceState("", cacheReadableSizeSema) {
            value = withContext(Dispatchers.IO) { chapterCache.readableSize }
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_storage_usage),
            preferenceItems = listOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(MR.strings.pref_storage_usage),
                ) {
                    BasePreferenceWidget(
                        subcomponent = {
                            StorageInfo(
                                modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                            )
                        },
                    )
                },

                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_chapter_cache),
                    subtitle = stringResource(MR.strings.used_cache, cacheReadableSize),
                    onClick = {
                        scope.launch {
                            try {
                                val deletedFiles = withContext(Dispatchers.IO) { chapterCache.clear() }
                                context.toast(context.stringResource(MR.strings.cache_deleted, deletedFiles))
                                cacheReadableSizeSema++
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                logcat(LogPriority.ERROR, e)
                                context.toast(MR.strings.cache_delete_error)
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.autoClearChapterCache,
                    title = stringResource(MR.strings.pref_auto_clear_chapter_cache),
                ),
            ),
        )
    }

    @Composable
    private fun getExportGroup(): Preference.PreferenceGroup {
        var showDialog by remember { mutableStateOf(false) }
        var exportOptions by remember {
            mutableStateOf(
                ExportOptions(
                    includeTitle = true,
                    includeAuthor = true,
                    includeArtist = true,
                ),
            )
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val getFavorites = remember { context.appGraph.getFavorites }
        var favorites by remember { mutableStateOf<List<Manga>>(emptyList()) }
        LaunchedEffect(Unit) {
            favorites = getFavorites.await()
        }

        val saveFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            uri?.let {
                scope.launch {
                    LibraryExporter.exportToCsv(
                        context = context,
                        uri = it,
                        favorites = favorites,
                        options = exportOptions,
                        onExportComplete = {
                            scope.launch(Dispatchers.Main) {
                                context.toast(MR.strings.library_exported)
                            }
                        },
                    )
                }
            }
        }

        if (showDialog) {
            ColumnSelectionDialog(
                options = exportOptions,
                onConfirm = { options ->
                    exportOptions = options
                    saveFileLauncher.launch("mihon_library.csv")
                },
                onDismissRequest = { showDialog = false },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.export),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.library_list),
                    onClick = { showDialog = true },
                ),
            ),
        )
    }

    @Composable
    private fun ColumnSelectionDialog(
        options: ExportOptions,
        onConfirm: (ExportOptions) -> Unit,
        onDismissRequest: () -> Unit,
    ) {
        var titleSelected by remember { mutableStateOf(options.includeTitle) }
        var authorSelected by remember { mutableStateOf(options.includeAuthor) }
        var artistSelected by remember { mutableStateOf(options.includeArtist) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = {
                Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
            },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = titleSelected,
                            onCheckedChange = { checked ->
                                titleSelected = checked
                                if (!checked) {
                                    authorSelected = false
                                    artistSelected = false
                                }
                            },
                        )
                        Text(text = stringResource(MR.strings.title))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = authorSelected,
                            onCheckedChange = { authorSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.author))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = artistSelected,
                            onCheckedChange = { artistSelected = it },
                            enabled = titleSelected,
                        )
                        Text(text = stringResource(MR.strings.artist))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm(
                            ExportOptions(
                                includeTitle = titleSelected,
                                includeAuthor = authorSelected,
                                includeArtist = artistSelected,
                            ),
                        )
                        onDismissRequest()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
