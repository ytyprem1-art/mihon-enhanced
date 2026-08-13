package eu.kanade.tachiyomi.ui.mod.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun WelcomeSheet(
    onDismissRequest: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    AdaptiveSheet(
        onDismissRequest = { onDismissRequest(dontShowAgain) },
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 16.dp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .weight(1f, fill = false)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.mod_welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(MR.strings.mod_welcome_subtitle),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = stringResource(MR.strings.mod_welcome_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )

                SectionHeader(stringResource(MR.strings.mod_welcome_section_features))
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_history_categories_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_history_categories_desc)
                )
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_history_groups_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_history_groups_desc)
                )
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_linked_sources_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_linked_sources_desc)
                )
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_quick_switcher_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_quick_switcher_desc)
                )
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_update_watch_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_update_watch_desc)
                )
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_bookmark_import_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_bookmark_import_desc)
                )
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_backup_restore_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_backup_restore_desc)
                )
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_migration_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_migration_desc)
                )
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_cloudflare_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_cloudflare_desc)
                )
                FeatureItem(
                    title = stringResource(MR.strings.mod_welcome_feature_grid_columns_title),
                    desc = stringResource(MR.strings.mod_welcome_feature_grid_columns_desc)
                )

                SectionHeader(stringResource(MR.strings.mod_welcome_section_credits))
                Text(
                    text = stringResource(MR.strings.mod_welcome_credit_mod_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(MR.strings.mod_welcome_credit_mod_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )

                val currentProjectLink = "https://github.com/ytyprem1-art/mihon-enhanced"
                val previousProjectLink = "https://github.com/ytyprem1-art/mihonHistory-category"

                Hyperlink(
                    text = "Current project",
                    url = currentProjectLink,
                    onClick = { uriHandler.openUri(currentProjectLink) }
                )
                Hyperlink(
                    text = "Previous project / v1.x",
                    url = previousProjectLink,
                    onClick = { uriHandler.openUri(previousProjectLink) }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(MR.strings.mod_welcome_credit_mihon_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(MR.strings.mod_welcome_credit_mihon_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )

                SectionHeader(stringResource(MR.strings.mod_welcome_section_feedback))
                Text(
                    text = stringResource(MR.strings.mod_welcome_feedback_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(MR.strings.mod_welcome_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabeledCheckbox(
                    label = stringResource(MR.strings.mod_welcome_dont_show_again),
                    checked = dontShowAgain,
                    onCheckedChange = { dontShowAgain = it },
                )

                Button(
                    onClick = { onDismissRequest(dontShowAgain) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(MR.strings.action_close))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun FeatureItem(title: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("DEPRECATION")
@Composable
private fun Hyperlink(text: String, url: String, onClick: () -> Unit) {
    val annotatedString = buildAnnotatedString {
        withStyle(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Medium
            )
        ) {
            append(text)
        }
    }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        ClickableText(
            text = annotatedString,
            onClick = { onClick() },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
