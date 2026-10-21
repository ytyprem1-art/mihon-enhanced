package eu.kanade.tachiyomi.ui.mod.appupdate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import tachiyomi.presentation.core.components.material.Scaffold
import java.io.File

private object ModUpdatePrompt {
    val release = kotlinx.coroutines.flow.MutableStateFlow<ModRelease?>(null)
}

@Composable
fun ModUpdateHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val release by ModUpdatePrompt.release.collectAsState()
    var checking by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!checking) scope.launch {
            checking = true
            try {
                val latest = withTimeout(15_000) { ModUpdateStore.latest(BuildConfig.VERSION_NAME) }
                if (latest != null && latest.newerThan(BuildConfig.VERSION_NAME)) {
                    ModUpdatePrompt.release.value = latest
                    notifyUpdate(context, latest)
                } else {
                    NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline/unconfigured backend must not interrupt reading or login.
            } finally {
                checking = false
            }
        }
    }
    release?.let { current -> ModUpdateOffer(current) { ModUpdatePrompt.release.value = null } }
}

private const val UPDATE_NOTIFICATION = 75421

private fun notifyUpdate(context: Context, release: ModRelease) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("enhanced_updates", "Mihon Enhanced updates", NotificationManager.IMPORTANCE_DEFAULT))
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    val pending = PendingIntent.getActivity(context, UPDATE_NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, "enhanced_updates")
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Mihon Enhanced ${release.version} tersedia")
        .setContentText("Buka aplikasi untuk download dan memasang update.")
        .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
    try { manager.notify(UPDATE_NOTIFICATION, notification) } catch (_: SecurityException) { }
}

@Composable
private fun ModUpdateOffer(release: ModRelease, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Mihon Enhanced ${release.version} tersedia") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (release.history.isEmpty()) Text(release.notes.ifBlank { "Versi baru siap diunduh." })
                else release.history.forEach { note ->
                    Text("Versi ${note.version}", style = MaterialTheme.typography.titleSmall)
                    Text(note.notes.ifBlank { "Pembaruan aplikasi." })
                }
                if (!release.historyAvailable) Text("Riwayat changelog belum dapat dimuat. Cek update lagi untuk mencoba ulang.")
                if (busy) {
                    val value = progress
                    if (value != null) {
                        LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth())
                        Text("Mengunduh ${(value * 100).toInt()}%")
                    } else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Menyiapkan unduhan…") }
                }
                if (apk != null) Text("Unduhan selesai. Tekan Pasang, lalu konfirmasi dari Android. Jika diminta izin pemasangan, aktifkan lalu kembali dan tekan Pasang lagi.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val downloaded = apk
                if (downloaded != null) {
                    try { ModApkDownload.install(context, downloaded) } catch (e: Exception) { error = e.message }
                } else job = scope.launch {
                    busy = true; error = null
                    try {
                        apk = ModApkDownload.download(context, release) { progress = it }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "Unduhan gagal. Silakan coba lagi." }
                    finally { busy = false }
                }
            }) { Text(if (apk != null) "Pasang" else "Update sekarang") }
        },
        dismissButton = {
            TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(if (busy) "Batalkan unduhan" else "Nanti") }
        },
    )
}

object ModUpdateSettingsScreen : Screen() {
    @Composable
    override fun Content() {
        val back = LocalBackPress.current
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var admin by remember { mutableStateOf(false) }
        var password by remember { mutableStateOf("") }
        var pinHash by remember { mutableStateOf("") }
        var version by remember { mutableStateOf("") }
        var link by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf<ModRelease?>(null) }
        fun action(block: suspend () -> Unit) {
            if (busy) return
            scope.launch {
                busy = true; message = null
                try { block() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "Gagal. Silakan coba lagi." }
                finally { busy = false }
            }
        }
        Scaffold(topBar = { AppBar(title = "Kelola update", navigateUp = back) }) { padding ->
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium)
                Button(enabled = !busy, onClick = { action {
                    val latest = ModUpdateStore.latest(BuildConfig.VERSION_NAME)
                    if (latest != null && latest.newerThan(BuildConfig.VERSION_NAME)) ModUpdatePrompt.release.value = latest
                    else message = "Aplikasi sudah menggunakan versi terbaru."
                } }) { Text("Cek update") }
                HorizontalDivider()
                Text("Admin update", style = MaterialTheme.typography.titleMedium)
                if (!admin) {
                    Text("Masukkan password admin untuk membuka menu penerbitan update.")
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && password.isNotBlank(), onClick = { action {
                        try { pinHash = ModUpdateStore.unlock(password); admin = true } finally { password = "" }
                    } }) { Text("Buka menu admin") }
                } else {
                    Text("Upload APK signed ke Google Drive dengan akses melalui link, lalu isi informasi rilisnya di sini.")
                    OutlinedTextField(version, { if (it.length <= 16) version = it }, label = { Text("Versi baru, misalnya 2.2") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(link, { if (it.length <= 2000) link = it }, label = { Text("Link APK Google Drive") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(notes, { if (it.length <= 4000) notes = it }, label = { Text("Catatan perubahan") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy, onClick = {
                        try {
                            require(ModVersion.parse(version.trim()) != null) { "Isi versi seperti 2.2 atau 2.2.1" }
                            val fileId = requireNotNull(ModRelease.driveFileId(link)) { "Gunakan link file APK Google Drive yang dapat diakses melalui link" }
                            val release = ModRelease(version.trim(), fileId, notes.trim())
                            require(release.number >= (ModVersion.parse(BuildConfig.VERSION_NAME)?.number ?: Long.MAX_VALUE)) { "Versi rilis tidak boleh lebih lama dari aplikasi ini" }
                            confirm = release
                        } catch (e: Exception) { message = e.message }
                    }) { Text("Terbitkan update") }
                    TextButton(enabled = !busy, onClick = { pinHash = ""; admin = false }) { Text("Kunci menu admin") }
                }
                if (busy) CircularProgressIndicator()
                message?.let { Text(it) }
            }
        }
        confirm?.let { release ->
            AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Terbitkan ${release.version}?") },
                text = { Text("Semua pengguna akan ditawari versi ini saat membuka aplikasi. Pastikan link menuju APK signed dengan versi dan kunci penandatanganan yang sesuai.") },
                confirmButton = { TextButton(onClick = { confirm = null; action {
                    ModUpdateStore.publish(release, pinHash)
                    message = "Update ${release.version} berhasil diterbitkan."
                } }) { Text("Terbitkan") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Batal") } })
        }
    }
}
