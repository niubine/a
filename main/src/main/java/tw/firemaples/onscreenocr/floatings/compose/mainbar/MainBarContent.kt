package tw.firemaples.onscreenocr.floatings.compose.mainbar

import android.content.res.Configuration
import android.graphics.Point
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
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

    Box(
        modifier = Modifier
            .alpha(if (state.drawMainBar) 1f else 0f)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
        ) {
            if (state.displaySelectButton) {
                Spacer(modifier = Modifier.size(4.dp))
                MainBarButton(
                    icon = R.drawable.ic_selection,
                    onClick = viewModel::onSelectClicked,
                )
            }
            if (state.displayTranslateButton) {
                Spacer(modifier = Modifier.size(4.dp))
                MainBarButton(
                    icon = R.drawable.ic_translate,
                    onClick = viewModel::onTranslateClicked,
                )
            }
            if (state.displayCloseButton) {
                Spacer(modifier = Modifier.size(4.dp))
                MainBarButton(
                    icon = R.drawable.ic_close,
                    onClick = viewModel::onCloseClicked,
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
            MenuButton(
                onClick = viewModel::onMenuButtonClicked,
                onDragStart = onDragStart,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
                onDrag = onDrag,
            )
        }
    }
}

@Composable
private fun MainBarButton(
    @DrawableRes
    icon: Int,
    onClick: () -> Unit,
) {
    Image(
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick)
            .background(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(4.dp))
            .padding(4.dp),
        painter = painterResource(id = icon),
        contentDescription = "",
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
    )
}

@Composable
private fun MenuButton(
    onClick: () -> Unit,
    onDragStart: (Offset) -> Unit = { },
    onDragEnd: () -> Unit = { },
    onDragCancel: () -> Unit = { },
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    Image(
        modifier = Modifier
            .size(32.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = onDragStart,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                    onDrag = onDrag,
                )
            }
            .clickable(onClick = onClick)
            .padding(2.dp),
        painter = painterResource(id = R.drawable.ic_menu_move),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        contentDescription = "",
    )
}

private class MainBarStateProvider : PreviewParameterProvider<MainBarState> {
    override val values: Sequence<MainBarState>
        get() = listOf(
            MainBarState(
                displaySelectButton = true,
                displayTranslateButton = true,
                displayCloseButton = true,
            ),
            MainBarState(
                displaySelectButton = true,
                displayTranslateButton = true,
                displayCloseButton = true,
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
        override fun onMenuItemClicked(key: String?) = Unit
        override fun onSelectClicked() = Unit
        override fun onTranslateClicked() = Unit
        override fun onCloseClicked() = Unit
        override fun onMenuButtonClicked() = Unit
        override fun onAttachedToScreen() = Unit
        override fun onDragEnd(x: Int, y: Int) = Unit
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
