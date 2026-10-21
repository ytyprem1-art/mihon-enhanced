package eu.kanade.tachiyomi.ui.mod.chatprofile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Displays the existing presence snapshot; opening a profile makes no network request. */
@Composable
fun ChatUserProfile(username: String, appVersion: String?, userId: String = "", avatarBase64: String? = null, avatarPhotoVersion: String? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ownProfile = userId.isNotBlank() && FirebaseAuth.getInstance().currentUser?.uid == userId
    var busy by remember(userId) { mutableStateOf(false) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    var cropUri by remember(userId) { mutableStateOf<android.net.Uri?>(null) }
    var photoPreview by remember(userId, avatarBase64, avatarPhotoVersion) { mutableStateOf<android.graphics.Bitmap?>(null) }
    suspend fun changePhoto(photo: ChatAvatarUpload?) = ChatProfilePhotoStore.change(userId, username, photo)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !busy) cropUri = uri
    }
    cropUri?.let { uri -> ChatAvatarCrop(uri, onDismiss = { cropUri = null }) { encoded ->
        cropUri = null
        scope.launch {
            busy = true
            error = null
            try {
                changePhoto(encoded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not update photo. Please retry."
            } finally {
                busy = false
            }
        }
    } }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Chat profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChatAvatar(username, avatarBase64, size = 72.dp, onPhotoClick = { photoPreview = it })
                Text(username, style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("App version", style = MaterialTheme.typography.labelMedium)
                    Text(appVersion?.takeIf { it.isNotBlank() } ?: "Version unavailable")
                }
                if (ownProfile) {
                    if (avatarBase64 != null && avatarPhotoVersion == null) Text(
                        "Choose your photo again for a sharper preview.", style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(enabled = !busy, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text("Change photo")
                    }
                    if (avatarBase64 != null) TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                changePhoto(null)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Could not remove photo. Please retry."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Remove photo") }
                }
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
    photoPreview?.let { bitmap ->
        ChatProfilePhotoPreview(userId, avatarPhotoVersion, bitmap) { photoPreview = null }
    }
}
