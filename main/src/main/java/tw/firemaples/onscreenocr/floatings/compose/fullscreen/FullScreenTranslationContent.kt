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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
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

    val displayBlocks = if (state.showOriginal) emptyList() else state.translatedBlocks

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

    }
}

@Composable
private fun OverlayText(
    block: OverlayTextBlock,
    rootOffsetX: Int,
    rootOffsetY: Int,
) {
    val density = LocalDensity.current
    val availableHeightPx = block.boundingBox.height().toFloat().coerceAtLeast(0f)
    val calculatedFontSize = with(density) { (availableHeightPx * 0.65f).toSp() }
    val fontSize = max(8f, min(14f, calculatedFontSize.value)).sp
    val textStyle = TextStyle(
        fontSize = fontSize,
        lineHeight = fontSize * 1.1f,
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
            overflow = TextOverflow.Visible,
        )
    }
}
