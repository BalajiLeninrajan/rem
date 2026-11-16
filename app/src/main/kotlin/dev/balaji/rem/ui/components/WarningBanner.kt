package dev.balaji.rem.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.balaji.rem.ui.theme.RemTheme

/** A warning with one action that leads to the screen where it can be fixed. */
@Composable
fun WarningBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                    Text(text = actionLabel, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Preview
@Composable
private fun WarningBannerPreview() {
    RemTheme {
        WarningBanner(
            message = "No barcode is registered. Alarms can be dismissed with a plain button.",
            actionLabel = "Add a barcode",
            onAction = {},
        )
    }
}
