package eu.kanade.tachiyomi.ui.mod.panelshare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatRetentionNotice() {
    val error by ChatPanelStore.cleanupError.collectAsState()
    error?.let {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = ChatPanelStore::requestCleanup) { Text("Retry cleanup") }
        }
    }
}
