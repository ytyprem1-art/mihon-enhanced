package eu.kanade.tachiyomi.ui.mod.updatewatch.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun AutoRefreshInactivityWarningDialog(
    days: Long,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val isStrongWarning = days >= 180

    val title = if (isStrongWarning) {
        "This manga may be inactive"
    } else {
        "Old latest release"
    }

    val body = if (isStrongWarning) {
        "This manga has not released a new chapter for $days days.\n\nIt may be inactive, completed, on hiatus, or no longer updated by this source. Auto Refresh can still monitor it, but repeated background checks may use additional battery and network data.\n\nDo you still want to enable Auto Refresh?"
    } else {
        "This manga has not released a new chapter for $days days.\n\nAuto Refresh is intended mainly for active series. Tracking inactive titles may cause unnecessary background checks and use additional battery and network data.\n\nDo you still want to enable Auto Refresh?"
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Enable anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
