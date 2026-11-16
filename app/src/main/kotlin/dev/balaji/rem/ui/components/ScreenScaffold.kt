@file:OptIn(ExperimentalMaterial3Api::class)

package dev.balaji.rem.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.balaji.rem.R

/** Widest a single column of content gets. Wider windows center it. */
val ContentMaxWidth: Dp = 640.dp

/**
 * The frame every screen below home shares: a top bar with a back arrow, optional actions,
 * a snackbar host, and content centered at [ContentMaxWidth].
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ui_action_back),
                        )
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        CenteredContent(
            // Consuming the bar insets first keeps imePadding from adding the navigation bar a second time.
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding(),
            content = content,
        )
    }
}

/** Fills the window and keeps [content] at most [ContentMaxWidth] wide, centered. */
@Composable
fun CenteredContent(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) {
            content()
        }
    }
}
