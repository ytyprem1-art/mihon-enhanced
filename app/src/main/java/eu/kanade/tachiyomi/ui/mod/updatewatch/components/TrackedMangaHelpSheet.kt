package eu.kanade.tachiyomi.ui.mod.updatewatch.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
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
import tachiyomi.presentation.core.components.material.padding

@Composable
fun TrackedMangaHelpSheet(
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
                if (page > 1) {
                    IconButton(onClick = { page-- }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                }

                Text(
                    text = when (page) {
                        1 -> "What Tracking Does"
                        2 -> "Status Timeline"
                        3 -> "Tracking Interval"
                        else -> "Difference from Auto Refresh"
                    },
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

            when (page) {
                1 -> PageOne(onNext = { page = 2 })
                2 -> PageTwo(onNext = { page = 3 })
                3 -> PageThree(onNext = { page = 4 })
                else -> PageFour()
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
            text = "Tracking uses locally known chapter release data to predict when the next update may arrive.",
            style = MaterialTheme.typography.bodyLarge,
        )

        BulletItem("It does not contact the source in the background when Auto Refresh is OFF.")
        BulletItem("It estimates the next arrival using your chosen expected update interval.")
        BulletItem("Manga only appear in Update Watch when they are close to or past this interval.")
        BulletItem("Tracking-only is much lighter on battery, data, and source requests than Auto Refresh.")

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("Next: Status Timeline")
        }
    }
}

@Composable
private fun PageTwo(onNext: () -> Unit) {
    Column(
        modifier = Modifier.padding(MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Text(
            text = "Manga move through different sections of Update Watch based on the expected interval (N days):",
            style = MaterialTheme.typography.bodyLarge,
        )

        TimelineItem("Before day N-1", "Hidden from Update Watch to keep your list clean.")
        TimelineItem("Day N-1", "Upcoming / Expected update tomorrow.")
        TimelineItem("Day N", "Due today.")
        TimelineItem("After day N", "Overdue.")

        Text(
            text = "Examples:",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )

        ExampleItem("2-day interval", "Day 1: Upcoming · Day 2: Due · Day 3+: Overdue")
        ExampleItem("7-day interval", "Day 6: Upcoming · Day 7: Due · Day 8+: Overdue")
        ExampleItem("30-day interval", "Day 29: Upcoming · Day 30: Due · Day 31+: Overdue")

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("Next: Tracking Interval")
        }
    }
}

@Composable
private fun PageThree(onNext: () -> Unit) {
    Column(
        modifier = Modifier.padding(MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Text(
            text = "The interval represents how often you expect a new chapter to be released.",
            style = MaterialTheme.typography.bodyLarge,
        )

        BulletItem("It is not a manual refresh timer.")
        BulletItem("It is still used when Auto Refresh is OFF to determine visibility.")
        BulletItem("You can choose presets (7, 14, 30 days) or a custom number of days.")
        BulletItem("Changing the interval immediately recalculates the status for that manga.")

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("Next: Difference from Auto Refresh")
        }
    }
}

@Composable
private fun PageFour() {
    Column(
        modifier = Modifier.padding(MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        Text(
            text = "Tracking-only",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        BulletItem("Predicts from local chapter dates.")
        BulletItem("No background source checks.")
        BulletItem("No refresh attempt history.")

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Auto Refresh (Optional Extension)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        BulletItem("Actively checks the source in the background.")
        BulletItem("Adds priority status (HOT/WARM/COLD/STALE).")
        BulletItem("Records attempt history and last checked times.")

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                text = "Turning Auto Refresh off does not remove the tracking " +
                    "interval. Your manga will still appear as Upcoming, " +
                    "Due, or Overdue based on local data.",
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
private fun TimelineItem(label: String, description: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExampleItem(title: String, details: String) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = details,
            style = MaterialTheme.typography.bodySmall,
            color =
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
