package dev.balaji.rem.ring.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.balaji.rem.R
import dev.balaji.rem.core.FallbackCode
import dev.balaji.rem.di.containerViewModel

/** Shows a fresh 40 character code and dismisses the ring when it is typed back. Paste is disabled. */
@Composable
fun TypedFallbackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = containerViewModel { TypedFallbackViewModel(it.ringController) }
    val typed = viewModel.typed
    val correct = viewModel.correctLength
    val hasMistake = typed.length > correct

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = stringResource(R.string.ring_typed_instruction), style = MaterialTheme.typography.titleMedium)
            Text(
                text = codeWithProgress(
                    code = viewModel.code,
                    correct = correct,
                    doneColor = MaterialTheme.colorScheme.primary,
                    nextColor = if (hasMistake) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    restColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                style = CODE_STYLE,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = viewModel::onTyped,
                label = { Text(stringResource(R.string.ring_typed_field_label)) },
                isError = hasMistake,
                supportingText = {
                    Text(
                        if (hasMistake) {
                            stringResource(R.string.ring_typed_wrong, correct + 1)
                        } else {
                            stringResource(R.string.ring_typed_progress, correct, FallbackCode.LENGTH)
                        },
                    )
                },
                textStyle = CODE_STYLE.copy(fontSize = 18.sp, lineHeight = 26.sp),
                // A password keyboard has no suggestions, no autocorrect and no learning.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.None,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.ring_typed_back))
            }
        }
    }
}

/**
 * The code in groups of [GROUP_SIZE], coloured by progress: the part already typed right,
 * the character to type next (underlined, and in the error colour after a mistake), and
 * the rest.
 */
private fun codeWithProgress(
    code: String,
    correct: Int,
    doneColor: Color,
    nextColor: Color,
    restColor: Color,
): AnnotatedString = buildAnnotatedString {
    code.forEachIndexed { index, char ->
        if (index > 0 && index % GROUP_SIZE == 0) append(' ')
        val style = when {
            index < correct -> SpanStyle(color = doneColor)
            index == correct -> SpanStyle(
                color = nextColor,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
            )
            else -> SpanStyle(color = restColor)
        }
        withStyle(style) { append(char) }
    }
}

private const val GROUP_SIZE = 5

private val CODE_STYLE = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 26.sp,
    lineHeight = 40.sp,
    letterSpacing = 1.sp,
)
