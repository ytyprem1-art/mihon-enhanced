package eu.kanade.tachiyomi.ui.mod.personalfavorites

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.components.AppBar
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun isPersonalFavorite(data: Any?): Boolean {
    if (LocalInspectionMode.current || data == null) return false
    return when (data) {
        is coil3.request.ImageRequest -> isPersonalFavorite(data.data)
        is Manga -> {
            val marks by PersonalFavorites.marks.collectAsStateWithLifecycle()
            data.url.isNotBlank() && data.personalFavoriteIdentity() in marks
        }
        is MangaCover -> {
            val ids by PersonalFavorites.coverIds.collectAsStateWithLifecycle()
            (data.sourceId to data.mangaId) in ids
        }
        else -> false
    }
}

@Composable
fun PersonalFavoriteBadge(data: Any?, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (isPersonalFavorite(data)) {
        val description = stringResource(MR.strings.mod_personal_favorites)
        Canvas(
            modifier = modifier.size(if (compact) 16.dp else 24.dp)
                .semantics { contentDescription = description },
        ) {
            val radius = size.minDimension / 2 - 1.dp.toPx()
            val path = Path().apply {
                repeat(10) { point ->
                    val angle = -Math.PI / 2 + point * Math.PI / 5
                    val distance = if (point % 2 == 0) radius else radius * 0.46f
                    val x = center.x + (cos(angle) * distance).toFloat()
                    val y = center.y + (sin(angle) * distance).toFloat()
                    if (point == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(path, Color(0xFFFFD54F))
            drawPath(path, Color(0xEE202020), style = Stroke(1.25.dp.toPx(), join = StrokeJoin.Round))
        }
    }
}

@Composable
fun PersonalFavoriteCover(
    data: Any?,
    modifier: Modifier,
    showBadge: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    if (showBadge) {
        BoxWithConstraints(modifier) {
            content()
            val compact = maxWidth < 80.dp
            PersonalFavoriteBadge(
                data,
                Modifier.align(Alignment.TopStart).padding(if (compact) 1.dp else 4.dp),
                compact = compact,
            )
        }
    } else {
        Box(modifier, content = content)
    }
}

@Composable
fun PersonalFavoriteGridBadges(
    data: Any?,
    modifier: Modifier = Modifier,
    badges: (@Composable RowScope.() -> Unit)?,
) {
    Column(modifier) {
        if (badges != null) BadgeGroup(content = badges)
        PersonalFavoriteBadge(data, Modifier.padding(top = if (badges != null) 2.dp else 0.dp))
    }
}

@Composable
fun personalFavoriteAction(manga: Manga): AppBar.OverflowAction {
    val marked = isPersonalFavorite(manga)
    return AppBar.OverflowAction(
        title = stringResource(
            if (marked) MR.strings.mod_personal_favorite_remove else MR.strings.mod_personal_favorite_add,
        ),
        onClick = { PersonalFavorites.store.toggle(manga.personalFavoriteIdentity()) },
    )
}
