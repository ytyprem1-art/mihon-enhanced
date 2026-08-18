package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Notifier class for displaying sync-related notifications.
 */
class SyncNotifier(private val context: Context) {

    private val completeNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_BACKUP_RESTORE_COMPLETE,
    ) {
        setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        setSmallIcon(R.drawable.ic_mihon)
        setAutoCancel(false)
    }

    private fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    /**
     * Shows a sync success notification.
     */
    fun showSyncSuccess(message: String) {
        with(completeNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.app_name))
            setContentText(message)
            show(ID_SYNC_COMPLETE)
        }
    }

    /**
     * Shows a sync error notification.
     */
    fun showSyncError(message: String) {
        context.cancelNotification(ID_SYNC_COMPLETE)

        with(completeNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.app_name))
            setContentText(message)
            show(ID_SYNC_ERROR)
        }
    }

    /**
     * Cancels any ongoing sync notifications.
     */
    fun cancelSyncNotifications() {
        context.cancelNotification(ID_SYNC_COMPLETE)
        context.cancelNotification(ID_SYNC_ERROR)
    }

    companion object {
        private const val ID_SYNC_COMPLETE = -1001
        private const val ID_SYNC_ERROR = -1002
    }
}
