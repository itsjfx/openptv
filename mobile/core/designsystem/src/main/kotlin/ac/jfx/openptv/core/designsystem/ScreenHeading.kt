package ac.jfx.openptv.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Hero heading rendered as the first item in a screen's content body. Pairs with a small
 * `TopAppBar` whose `title = {}` to reproduce the ReadYou layout — gear sits in a compact icon
 * row pinned under the status bar, large title lives below it inside the content.
 *
 * `LargeTopAppBar` was the previous shape, but its two-row layout pushes the navigation icon
 * down into the expanded title section, which read as too low against the status bar.
 *
 * Padding mirrors ReadYou's `DisplayText` (`start = 24.dp, top = 48.dp, end = 24.dp,
 * bottom = 24.dp`).
 */
@Composable
fun ScreenHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 48.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
