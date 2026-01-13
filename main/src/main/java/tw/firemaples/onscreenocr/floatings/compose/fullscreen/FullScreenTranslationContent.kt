package tw.firemaples.onscreenocr.floatings.compose.fullscreen

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.firemaples.onscreenocr.R
import tw.firemaples.onscreenocr.floatings.compose.base.pxToDp
import tw.firemaples.onscreenocr.floatings.manager.OverlayTextBlock

@Composable
fun FullScreenTranslationContent(
    viewModel: FullScreenTranslationViewModel,
    requestRootLocationOnScreen: () -> Rect,
) {
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 64.dp.toPx() }

    LaunchedEffect(Unit) {
        val rootLocation = requestRootLocationOnScreen.invoke()
        viewModel.onRootViewPositioned(
            xOffset = rootLocation.left,
            yOffset = rootLocation.top,
        )
    }

    val displayBlocks = if (state.showOriginal || state.translatedBlocks.isEmpty()) {
        state.originalBlocks
    } else {
        state.translatedBlocks
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    viewModel.onPressStart()
                    val startPosition = down.position
                    var shouldDismiss = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        if (change.position.y - startPosition.y < -swipeThreshold) {
                            shouldDismiss = true
                            viewModel.onSwipeToDismiss()
                            break
                        }
                    }
                    if (!shouldDismiss) {
                        viewModel.onPressEnd()
                    }
                }
            }
    ) {
        displayBlocks.filter { it.text.isNotBlank() }.forEach { block ->
            OverlayText(
                block = block,
                rootOffsetX = state.rootOffset.x,
                rootOffsetY = state.rootOffset.y,
            )
        }

        if (state.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        Text(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            text = stringResource(id = R.string.full_screen_translation_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun OverlayText(
    block: OverlayTextBlock,
    rootOffsetX: Int,
    rootOffsetY: Int,
) {
    val textStyle = TextStyle(
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Box(
        modifier = Modifier
            .absoluteOffset(
                x = (block.boundingBox.left - rootOffsetX).pxToDp(),
                y = (block.boundingBox.top - rootOffsetY).pxToDp(),
            )
            .size(
                width = block.boundingBox.width().pxToDp(),
                height = block.boundingBox.height().pxToDp(),
            )
    ) {
        Text(
            text = block.text,
            style = textStyle,
        )
    }
}
