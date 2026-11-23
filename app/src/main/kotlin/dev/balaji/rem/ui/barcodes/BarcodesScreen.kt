package dev.balaji.rem.ui.barcodes

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.di.containerViewModel
import dev.balaji.rem.ui.components.ScreenScaffold
import dev.balaji.rem.ui.system.SystemSettings
import dev.balaji.rem.ui.system.hasPermission
import dev.balaji.rem.ui.theme.RemTheme

const val MAX_BARCODE_LABEL_LENGTH = 40

/** Registered barcodes with rename and delete. [onScan] opens the scanner to add a barcode or to test one. */
@Composable
fun BarcodesScreen(
    onScan: (testOnly: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = containerViewModel { BarcodesViewModel(it.barcodeRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The scan that waits for the camera permission: its testOnly flag, or null when none waits.
    var pendingScan by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var showDenied by rememberSaveable { mutableStateOf(false) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val testOnly = pendingScan
        pendingScan = null
        if (!granted) {
            showDenied = true
        } else if (testOnly != null) {
            onScan(testOnly)
        }
    }
    val startScan: (Boolean) -> Unit = { testOnly ->
        if (context.hasPermission(Manifest.permission.CAMERA)) {
            onScan(testOnly)
        } else {
            pendingScan = testOnly
            showRationale = true
        }
    }

    BarcodesContent(
        state = state,
        onAdd = { startScan(false) },
        onTest = { startScan(true) },
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onBack = onBack,
        modifier = modifier,
    )

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.ui_barcodes_camera_rationale_title)) },
            text = { Text(stringResource(R.string.ui_barcodes_camera_rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        permissionRequest.launch(Manifest.permission.CAMERA)
                    },
                ) { Text(stringResource(R.string.ui_action_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text(stringResource(R.string.ui_action_cancel)) }
            },
        )
    }
    if (showDenied) {
        AlertDialog(
            onDismissRequest = { showDenied = false },
            title = { Text(stringResource(R.string.ui_barcodes_camera_denied_title)) },
            text = { Text(stringResource(R.string.ui_barcodes_camera_denied)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDenied = false
                        SystemSettings.openAppDetails(context)
                    },
                ) { Text(stringResource(R.string.ui_action_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showDenied = false }) { Text(stringResource(R.string.ui_action_cancel)) }
            },
        )
    }
}

@Composable
fun BarcodesContent(
    state: BarcodesUiState,
    onAdd: () -> Unit,
    onTest: () -> Unit,
    onRename: (id: Long, label: String) -> Unit,
    onDelete: (id: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    ScreenScaffold(
        title = stringResource(R.string.screen_barcodes),
        onBack = onBack,
        modifier = modifier,
        actions = {
            TextButton(onClick = onTest, enabled = state.barcodes.isNotEmpty()) {
                Text(stringResource(R.string.ui_barcodes_test_scan))
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.ui_barcodes_add)) },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // The bottom padding keeps the last row clear of the floating button.
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = stringResource(
                        if (state.barcodes.isEmpty() && !state.loading) {
                            R.string.ui_barcodes_empty
                        } else {
                            R.string.ui_barcodes_intro
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(state.barcodes, key = { it.id }) { barcode ->
                BarcodeRow(barcode, onRename = { renameId = barcode.id }, onDelete = { deleteId = barcode.id })
            }
        }
    }

    state.barcodes.firstOrNull { it.id == renameId }?.let { barcode ->
        RenameDialog(
            barcode = barcode,
            onConfirm = { label ->
                renameId = null
                onRename(barcode.id, label)
            },
            onDismiss = { renameId = null },
        )
    }
    state.barcodes.firstOrNull { it.id == deleteId }?.let { barcode ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.ui_barcodes_delete_title, barcode.label)) },
            text = {
                Text(
                    stringResource(
                        if (state.barcodes.size == 1) R.string.ui_barcodes_delete_last else R.string.ui_barcodes_delete_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteId = null
                        onDelete(barcode.id)
                    },
                ) { Text(stringResource(R.string.ui_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.ui_action_cancel)) }
            },
        )
    }
}

@Composable
private fun BarcodeRow(barcode: Barcode, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
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
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ui_action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_action_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_action_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun RenameDialog(barcode: Barcode, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var label by rememberSaveable(barcode.id) { mutableStateOf(barcode.label) }
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

@Preview
@Composable
private fun BarcodesPreview() {
    RemTheme {
        BarcodesContent(
            state = BarcodesUiState(
                loading = false,
                barcodes = listOf(
                    Barcode(1, "Toothpaste", BarcodeFormat.EAN_13, "4006381333931"),
                    Barcode(2, "Coffee tin", BarcodeFormat.QR_CODE, "https://example.com/coffee"),
                ),
            ),
            onAdd = {},
            onTest = {},
            onRename = { _, _ -> },
            onDelete = {},
            onBack = {},
        )
    }
}
