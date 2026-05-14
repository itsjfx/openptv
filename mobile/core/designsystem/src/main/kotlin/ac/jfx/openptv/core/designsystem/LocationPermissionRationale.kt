package ac.jfx.openptv.core.designsystem

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Material 3 rationale shown the **first time** a user lands on a feature that needs location.
 * Pure UI — the caller owns the permission launch state (`rememberLauncherForActivityResult`) and
 * the "have we shown this before" flag.
 *
 * Lives in `:core:designsystem` rather than a feature module because the same dialog will be
 * reused by `:feature:nearby` (issue #37) and a future favourites "Nearest" sort. If a dedicated
 * `:core:ui` module materialises later (cross-cutting composables that aren't tokens or themes),
 * this file moves there unchanged.
 *
 * Copy intentionally explicit about "coarse" and "never background" — sensitive permissions earn
 * sentences, not bullets. The "Not now" dismiss is deliberately soft: a user who taps it can
 * still re-enter the feature later and see the prompt again, which matches Material's guidance
 * for resumable consent flows.
 */
@Composable
fun LocationPermissionRationale(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.core_designsystem_location_rationale_title)) },
        text = { Text(text = stringResource(R.string.core_designsystem_location_rationale_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.core_designsystem_location_rationale_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.core_designsystem_location_rationale_dismiss))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun LocationPermissionRationalePreview() {
    OpenPtvTheme(themeMode = ThemeMode.System) {
        LocationPermissionRationale(onConfirm = {}, onDismiss = {})
    }
}
