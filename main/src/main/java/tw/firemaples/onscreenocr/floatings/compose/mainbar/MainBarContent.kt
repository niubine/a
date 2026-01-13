package tw.firemaples.onscreenocr.floatings.compose.mainbar

import android.content.res.Configuration
import android.graphics.Point
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import tw.firemaples.onscreenocr.R
import tw.firemaples.onscreenocr.theme.AppTheme

@Composable
fun MainBarContent(
    viewModel: MainBarViewModel,
    onDragStart: (Offset) -> Unit = { },
    onDragEnd: () -> Unit = { },
    onDragCancel: () -> Unit = { },
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val isLandscape =
        androidx.compose.ui.platform.LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .alpha(if (state.drawMainBar) 1f else 0f)
    ) {
        BoxWithConstraints {
            val maxToolbarWidth = maxWidth - FloatingBallSize - 12.dp
            val forceColumnLayout = isLandscape && maxToolbarWidth < 180.dp

            if (isLandscape && !forceColumnLayout) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (state.toolbarVisible) {
                        FloatingToolbar(
                            state = state,
                            isLandscape = true,
                            maxWidth = maxToolbarWidth,
                            onRegionCaptureClicked = viewModel::onRegionCaptureClicked,
                            onFullScreenCaptureClicked = viewModel::onFullScreenCaptureClicked,
                            onFullScreenTranslateClicked = viewModel::onFullScreenTranslateClicked,
                            onSettingsClicked = viewModel::onSettingsClicked,
                            onCancelClicked = viewModel::onCancelClicked,
                            onLanguageBlockClicked = viewModel::onLanguageBlockClicked,
                        )
                    }
                    FloatingBall(
                        onClick = viewModel::onFloatingBallClicked,
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                        onDrag = onDrag,
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (state.toolbarVisible) {
                        val toolbarMaxWidth = (this@BoxWithConstraints.maxWidth - 12.dp)
                        FloatingToolbar(
                            state = state,
                            isLandscape = false,
                            maxWidth = toolbarMaxWidth,
                            onRegionCaptureClicked = viewModel::onRegionCaptureClicked,
                            onFullScreenCaptureClicked = viewModel::onFullScreenCaptureClicked,
                            onFullScreenTranslateClicked = viewModel::onFullScreenTranslateClicked,
                            onSettingsClicked = viewModel::onSettingsClicked,
                            onCancelClicked = viewModel::onCancelClicked,
                            onLanguageBlockClicked = viewModel::onLanguageBlockClicked,
                        )
                    }
                    FloatingBall(
                        onClick = viewModel::onFloatingBallClicked,
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                        onDrag = onDrag,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingToolbar(
    state: MainBarState,
    isLandscape: Boolean,
    maxWidth: Dp,
    onRegionCaptureClicked: () -> Unit,
    onFullScreenCaptureClicked: () -> Unit,
    onFullScreenTranslateClicked: () -> Unit,
    onSettingsClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    onLanguageBlockClicked: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        LanguageBlock(
            langText = state.langText,
            translatorIcon = state.translatorIcon,
            onClick = onLanguageBlockClicked,
        )
        Spacer(modifier = Modifier.size(4.dp))
        ToolbarButton(
            icon = R.drawable.ic_selection,
            enabled = state.isIdle || state.canStartRegionCapture,
            onClick = onRegionCaptureClicked,
        )
        ToolbarButton(
            icon = R.drawable.ic_text_search,
            enabled = state.isIdle,
            onClick = onFullScreenCaptureClicked,
        )
        ToolbarButton(
            icon = R.drawable.ic_translate,
            enabled = state.isIdle,
            onClick = onFullScreenTranslateClicked,
        )
        ToolbarButton(
            icon = R.drawable.ic_settings,
            enabled = true,
            onClick = onSettingsClicked,
        )
        if (state.showCancelButton) {
            ToolbarButton(
                icon = R.drawable.ic_close,
                enabled = true,
                onClick = onCancelClicked,
            )
        }
    }

    val containerModifier = Modifier
        .widthIn(max = maxWidth)
        .background(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
        )
        .padding(6.dp)

    if (isLandscape) {
        Row(
            modifier = containerModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    } else {
        Column(
            modifier = containerModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun LanguageBlock(
    langText: String,
    translatorIcon: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = langText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (translatorIcon != null) {
            Image(
                painter = painterResource(id = translatorIcon),
                contentDescription = "",
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

@Composable
private fun ToolbarButton(
    @DrawableRes icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            modifier = Modifier.size(20.dp),
            painter = painterResource(id = icon),
            contentDescription = "",
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
        )
    }
}

@Composable
private fun FloatingBall(
    onClick: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(FloatingBallSize)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = onDragStart,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                    onDrag = onDrag,
                )
            }
            .clickable(onClick = onClick)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            )
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_translate),
            contentDescription = "",
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
        )
    }
}

private val FloatingBallSize = 48.dp

private class MainBarStateProvider : PreviewParameterProvider<MainBarState> {
    override val values: Sequence<MainBarState>
        get() = listOf(
            MainBarState(
                langText = "en>",
                translatorIcon = R.drawable.ic_google_translate_dark_grey,
                toolbarVisible = true,
                isIdle = true,
            ),
            MainBarState(
                langText = "en>tw",
                translatorIcon = null,
                toolbarVisible = true,
                isIdle = false,
                canStartRegionCapture = true,
                showCancelButton = true,
            )
        ).asSequence()

}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainBarContentPreview(
    @PreviewParameter(MainBarStateProvider::class) state: MainBarState,
) {
    val viewModel = object : MainBarViewModel {
        override val state: StateFlow<MainBarState>
            get() = MutableStateFlow(state)
        override val action: SharedFlow<MainBarAction>
            get() = MutableSharedFlow()

        override fun getInitialPosition(): Point = Point()
        override fun getFadeOutAfterMoved(): Boolean = false
        override fun getFadeOutDelay(): Long = 0L
        override fun getFadeOutDestinationAlpha(): Float = 0f
        override fun onFloatingBallClicked() = Unit
        override fun onRegionCaptureClicked() = Unit
        override fun onFullScreenCaptureClicked() = Unit
        override fun onFullScreenTranslateClicked() = Unit
        override fun onSettingsClicked() = Unit
        override fun onCancelClicked() = Unit
        override fun onAttachedToScreen() = Unit
        override fun onDragEnd(x: Int, y: Int) = Unit
        override fun onLanguageBlockClicked() = Unit
    }

    AppTheme {
        MainBarContent(
            viewModel = viewModel,
            onDragStart = { offset -> },
            onDragEnd = {},
            onDragCancel = {},
            onDrag = { change, dragAmount -> },
        )
    }

}
