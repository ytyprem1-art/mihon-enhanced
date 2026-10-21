package eu.kanade.tachiyomi.ui.mod.updatewatch.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import tachiyomi.core.common.Constants
import tachiyomi.domain.history.model.UpdateWatchInboxItem

class UpdateWatchNotifier(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    fun showUpdateNotification(items: List<UpdateWatchInboxItem>) {
        if (items.isEmpty()) return

        val builder = context.notificationBuilder(Notifications.CHANNEL_NEW_CHAPTERS) {
            setSmallIcon(R.drawable.ic_mihon)
            setAutoCancel(true)
            setContentIntent(createClickPendingIntent())
        }

        if (items.size == 1) {
            val item = items.first()
            builder.apply {
                setContentTitle(item.mangaTitle)
                setContentText("${item.chapterCount} new chapters found on ${item.sourceName}")
            }
        } else {
            val totalChapters = items.sumOf { it.chapterCount }
            builder.apply {
                setContentTitle("Tracked updates found")
                setContentText("$totalChapters new chapters found across ${items.size} manga")
            }
        }

        try {
            notificationManager.notify(NOTIFICATION_TAG, Notifications.ID_NEW_CHAPTERS, builder.build())
        } catch (e: SecurityException) {
            // Permission not granted, ignore
        }
    }

    fun updateAfterDismiss(items: List<UpdateWatchInboxItem>, enabled: Boolean) {
        val updates = items.filter { it.type == UpdateWatchInboxItem.TYPE_UPDATE }
        if (!enabled || updates.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_TAG, Notifications.ID_NEW_CHAPTERS)
        } else {
            showUpdateNotification(updates)
        }
    }

    private fun createClickPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = SHORTCUT_UPDATE_WATCH
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val NOTIFICATION_TAG = "update_watch"
        const val SHORTCUT_UPDATE_WATCH = "eu.kanade.tachiyomi.SHOW_UPDATE_WATCH"
    }
}
