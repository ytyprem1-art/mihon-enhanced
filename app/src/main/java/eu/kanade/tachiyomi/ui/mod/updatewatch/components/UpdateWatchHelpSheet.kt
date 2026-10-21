package eu.kanade.tachiyomi.ui.mod.updatewatch.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.mod.updatewatch.helper.UpdateWatchRefreshHelper
import tachiyomi.presentation.core.components.material.padding

@Composable
fun UpdateWatchHelpSheet(
    onDismissRequest: () -> Unit,
) {
    var page by remember { mutableIntStateOf(1) }

    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.padding.medium)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page == 2) {
                    IconButton(onClick = { page = 1 }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                }

                Text(
                    text = if (page == 1) "How Auto Refresh Works" else "Check Priority & Limits",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )

                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close",
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.small))

            if (page == 1) {
                PageOne(onNext = { page = 2 })
            } else {
                PageTwo()
            }
        }
    }
}

@Composable
private fun PageOne(onNext: () -> Unit) {
    Column(
        modifier = Modifier.padding(MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Text(
            text = "What Auto Refresh does",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "Auto Refresh checks selected tracked manga around the time they are expected to update.",
            style = MaterialTheme.typography.bodyLarge,
        )

        BulletItem("Upcoming manga are not checked yet.")
        BulletItem("Manga close to their expected update day are checked more often.")
        BulletItem("If no update is found, checks gradually slow down.")
        BulletItem("Weekly manga enter a faster checking window again during the next weekly cycle.")
        BulletItem("Rapid-update series use a faster schedule.")
        BulletItem("Older inactive series are checked less often.")

        Text(
            text = "Fixed refresh schedule",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "To keep updates predictable and consistent, Auto " +
                "Refresh follows fixed device-local wall-clock " +
                "slots:",
            style = MaterialTheme.typography.bodyMedium,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BulletItem("HOT: Every 2 hours (00:00, 02:00, 04:00, etc.)")
            BulletItem("WARM: Every 4 hours (00:00, 04:00, 08:00, 12:00, 16:00, 20:00)")
            BulletItem("COLD: Every 12 hours (00:00 and 12:00)")
            BulletItem("STALE: Once daily (00:00)")
        }

        Text(
            text = "A small randomized safety delay is added after " +
                "the base slot to avoid hitting sources exactly " +
                "on the hour. Android may also execute background " +
                "work slightly later due to system battery or network " +
                "restrictions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "If several slots are missed (for example, while " +
                "the device is off), only one catch-up refresh " +
                "is performed for the latest elapsed slot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Old or inactive manga",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "Auto Refresh is intended mainly for active series. " +
                "If the latest known chapter is already old, Mihon " +
                "shows a confirmation warning before enabling:",
            style = MaterialTheme.typography.bodyMedium,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BulletItem("60–179 days old: Standard inactivity warning.")
            BulletItem("180+ days old: Stronger warning that the manga may be inactive, completed, or on hiatus.")
        }

        Text(
            text = "The user may still choose “Enable anyway.” This " +
                "warning is based on how old the latest chapter " +
                "already is, and is separate from later monitoring " +
                "warnings (28/60/90 days) which track how long " +
                "Auto Refresh has been active without finding new " +
                "content.\n\nNo new network request is made for " +
                "this warning, and missing release dates do not " +
                "block enabling.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "How this differs from standard Mihon",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "Standard Mihon does not perform this per-manga " +
                "Auto Refresh behavior in the background.\n\nNormally, " +
                "chapter updates are found when you manually refresh " +
                "or when Mihon performs its regular update flow.\n\nUpdate " +
                "Watch Auto Refresh adds optional background checks " +
                "for selected manga based on their expected update " +
                "timing.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Auto Refresh does not guarantee an update will " +
                    "be found immediately. Sources may be delayed, " +
                    "unavailable, or temporarily block requests.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(MaterialTheme.padding.medium),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            onClick = onNext,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = "MUST READ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = "Important: How Check Priority & Limits Work",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "Learn why some manga are checked first, why others " +
                            "may wait, and how Auto Refresh can affect battery " +
                            "usage.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun PageTwo() {
    Column(
        modifier = Modifier.padding(MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Text(
            text = "Check priority",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        PriorityBucketItem(
            name = "HOT",
            frequency = "Every 2 hours",
            description = "Manga inside the most likely update window. Checked first and more often.",
            limit = UpdateWatchRefreshHelper.CAP_HOT,
        )
        PriorityBucketItem(
            name = "WARM",
            frequency = "Every 4 hours",
            description = "Manga slightly past its most likely update window. Checked after HOT manga.",
            limit = UpdateWatchRefreshHelper.CAP_WARM,
        )
        PriorityBucketItem(
            name = "COLD",
            frequency = "Every 12 hours",
            description = "Manga still waiting for an update. Checked less often.",
            limit = UpdateWatchRefreshHelper.CAP_COLD,
        )
        PriorityBucketItem(
            name = "STALE",
            frequency = "Once daily",
            description = "Manga with no update for a long time. Lowest queue " +
                "priority outside its recurring fast window. Auto " +
                "Refresh is not disabled automatically. You may " +
                "receive an inbox warning asking whether Auto Refresh " +
                "should continue.",
            limit = UpdateWatchRefreshHelper.CAP_STALE,
        )

        HorizontalDivider()

        Text(
            text = "Per-source limits",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        BulletItem("Maximum per source: ${UpdateWatchRefreshHelper.CAP_TOTAL} manga per run")
        BulletItem("Maximum active sources: ${UpdateWatchRefreshHelper.GLOBAL_CONCURRENCY} at the same time")

        Text(
            text = "These limits are not a limit on how many manga " +
                "you can track. Manga that are skipped because " +
                "of these limits remain due for the current wall-clock " +
                "slot and will be reconsidered during a later background " +
                "run.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = "HOT candidates are always selected before WARM, " +
                "COLD, and STALE candidates. Manga from the same " +
                "source are checked one by one. Different sources " +
                "may run concurrently, with staggered starts to " +
                "avoid a sudden burst of requests.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Limits help reduce rate limits, temporary blocks, and protection errors.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Text(
            text = "Battery and background activity",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "Standard Mihon does not perform this per-manga " +
                "background checking behavior.\n\nWhen Auto Refresh " +
                "is enabled, this mod may wake in the background, " +
                "connect to sources, and check chapter lists even " +
                "when the app is not open.\n\nEnabling Auto Refresh " +
                "for more manga may increase background activity, " +
                "network usage, and battery consumption—especially " +
                "when many manga become eligible at the same time.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = "Recommendations:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        BulletItem("Enable Auto Refresh only for manga you actively follow.")
        BulletItem("Use slower profiles for manga that update infrequently.")
        BulletItem("Disable Auto Refresh for completed or inactive series.")

        Text(
            text = "Tracking very old or inactive manga may create " +
                "unnecessary background checks. Mihon warns before " +
                "enabling Auto Refresh, but users may continue " +
                "if they still want to monitor the title.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Auto Refresh uses wall-clock slots, priority limits, " +
                    "staggered source starts, and per-source queues " +
                    "to reduce unnecessary activity.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(MaterialTheme.padding.medium),
            )
        }
    }
}

@Composable
private fun BulletItem(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = "•", modifier = Modifier.padding(end = 8.dp))
        Text(text = text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PriorityBucketItem(name: String, frequency: String, description: String, limit: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = frequency,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = "Max $limit",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
    }
}
