package dev.balaji.rem.ui.alarm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.balaji.rem.R
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.ui.barcodes.MAX_BARCODE_LABEL_LENGTH
import dev.balaji.rem.ui.barcodes.barcodeDescription
import dev.balaji.rem.ui.theme.RemTheme

/**
 * The barcodes that stop this alarm, with a delete button each, and buttons that open the
 * scanner to add one or to test a scan. Tapping a barcode renames it.
 */
@Composable
fun AlarmBarcodesSection(
    barcodes: List<Barcode>,
    onAdd: () -> Unit,
    onTest: () -> Unit,
    onRename: (Barcode, String) -> Unit,
    onRemove: (Barcode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A barcode of a new alarm has no id yet, so a row is identified by its key.
    var renameKey by rememberSaveable { mutableStateOf<String?>(null) }
    var removeKey by rememberSaveable { mutableStateOf<String?>(null) }
    val renameLabel = stringResource(R.string.ui_action_rename)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ui_editor_barcodes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            text = stringResource(if (barcodes.isEmpty()) R.string.ui_barcodes_empty else R.string.ui_barcodes_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = if (barcodes.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        barcodes.forEach { barcode ->
            ListItem(
                modifier = Modifier.clickable(onClickLabel = renameLabel) { renameKey = barcode.rowKey },
                leadingContent = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                headlineContent = { Text(barcode.label) },
                supportingContent = {
                    Text(
                        text = barcodeDescription(barcode.format, barcode.value),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { removeKey = barcode.rowKey }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.ui_barcodes_delete_description, barcode.label),
                        )
                    }
                },
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_barcodes_add))
            }
            TextButton(onClick = onTest, enabled = barcodes.isNotEmpty()) {
                Text(stringResource(R.string.ui_barcodes_test_scan))
            }
        }
    }

    barcodes.firstOrNull { it.rowKey == renameKey }?.let { barcode ->
        RenameDialog(
            barcode = barcode,
            onConfirm = { label ->
                renameKey = null
                onRename(barcode, label)
            },
            onDismiss = { renameKey = null },
        )
    }
    barcodes.firstOrNull { it.rowKey == removeKey }?.let { barcode ->
        AlertDialog(
            onDismissRequest = { removeKey = null },
            title = { Text(stringResource(R.string.ui_barcodes_delete_title, barcode.label)) },
            text = {
                Text(
                    stringResource(
                        if (barcodes.size == 1) R.string.ui_barcodes_delete_last else R.string.ui_barcodes_delete_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeKey = null
                        onRemove(barcode)
                    },
                ) { Text(stringResource(R.string.ui_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { removeKey = null }) { Text(stringResource(R.string.ui_action_cancel)) }
            },
        )
    }
}

@Composable
private fun RenameDialog(barcode: Barcode, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var label by rememberSaveable(barcode.rowKey) { mutableStateOf(barcode.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_barcodes_rename_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { if (it.length <= MAX_BARCODE_LABEL_LENGTH) label = it },
                label = { Text(stringResource(R.string.ui_barcodes_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label) }, enabled = label.isNotBlank()) {
                Text(stringResource(R.string.ui_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_action_cancel)) }
        },
    )
}

private val Barcode.rowKey: String get() = "${format.name}:$value"

@Preview
@Composable
private fun AlarmBarcodesSectionPreview() {
    RemTheme {
        AlarmBarcodesSection(
            barcodes = listOf(
                Barcode(1, "Toothpaste", BarcodeFormat.EAN_13, "4006381333931"),
                Barcode(2, "Coffee tin", BarcodeFormat.QR_CODE, "https://example.com/coffee"),
            ),
            onAdd = {},
            onTest = {},
            onRename = { _, _ -> },
            onRemove = {},
        )
    }
}
