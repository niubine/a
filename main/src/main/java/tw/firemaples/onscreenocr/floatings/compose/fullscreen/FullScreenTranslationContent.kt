package tw.firemaples.onscreenocr.floatings.compose.fullscreen

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.firemaples.onscreenocr.R
import tw.firemaples.onscreenocr.floatings.compose.base.pxToDp
import tw.firemaples.onscreenocr.floatings.manager.OverlayTextBlock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    val displayBlocks = if (state.showOriginal) {
        emptyList()
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

        if (state.isProcessing && !state.showOriginal) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        if (!state.showOriginal) {
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
}

@Composable
private fun OverlayText(
    block: OverlayTextBlock,
    rootOffsetX: Int,
    rootOffsetY: Int,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val rawPaddingHorizontalPx = with(density) { 4.dp.toPx() }
    val rawPaddingVerticalPx = with(density) { 2.dp.toPx() }
    val boxWidthPx = block.boundingBox.width().toFloat()
    val boxHeightPx = block.boundingBox.height().toFloat()
    val paddingHorizontalPx = min(rawPaddingHorizontalPx, boxWidthPx * 0.15f)
    val paddingVerticalPx = min(rawPaddingVerticalPx, boxHeightPx * 0.2f)
    val maxHeightPx = min(
        max(boxHeightPx, boxHeightPx * MAX_HEIGHT_MULTIPLIER),
        with(density) { MAX_HEIGHT_DP.dp.toPx() },
    )
    val availableWidthPx = max(1f, boxWidthPx - paddingHorizontalPx * 2f)
    val availableHeightPx = max(1f, maxHeightPx - paddingVerticalPx * 2f)
    val minFontSizePx = with(density) { MIN_FONT_SP.sp.toPx() }
    val maxFontSizePx = min(
        with(density) { MAX_FONT_SP.sp.toPx() },
        availableHeightPx,
    )
    val densityValue = density.density
    val fontScale = density.fontScale
    val fittedText = remember(
        block.text,
        availableWidthPx,
        availableHeightPx,
        densityValue,
        fontScale,
    ) {
        fitTextSize(
            textMeasurer = textMeasurer,
            text = block.text,
            maxWidthPx = availableWidthPx,
            maxHeightPx = availableHeightPx,
            minFontPx = minFontSizePx,
            maxFontPx = maxFontSizePx,
            density = density,
        )
    }
    val fontSizeSp = with(density) { fittedText.fontSizePx.toSp() }
    val maxLines = max(1, fittedText.layoutResult.lineCount)
    val textStyle = TextStyle(
        fontSize = fontSizeSp,
        color = Color.White,
    )
    val shape = RoundedCornerShape(4.dp)
    val backgroundColor = Color(0xCC000000)
    val paddingHorizontalDp = with(density) { paddingHorizontalPx.toDp() }
    val paddingVerticalDp = with(density) { paddingVerticalPx.toDp() }
    val minHeightDp = with(density) { boxHeightPx.toDp() }
    val maxHeightDp = with(density) { maxHeightPx.toDp() }

    Box(
        modifier = Modifier
            .absoluteOffset(
                x = (block.boundingBox.left - rootOffsetX).pxToDp(),
                y = (block.boundingBox.top - rootOffsetY).pxToDp(),
            )
            .width(block.boundingBox.width().pxToDp())
            .heightIn(min = minHeightDp, max = maxHeightDp)
            .wrapContentHeight()
            .background(backgroundColor, shape)
            .padding(horizontal = paddingHorizontalDp, vertical = paddingVerticalDp)
    ) {
        Text(
            text = block.text,
            style = textStyle,
            maxLines = maxLines,
            softWrap = true,
            overflow = TextOverflow.Clip,
        )
    }
}

private data class FittedText(
    val fontSizePx: Float,
    val layoutResult: androidx.compose.ui.text.TextLayoutResult,
)

private fun fitTextSize(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    maxWidthPx: Float,
    maxHeightPx: Float,
    minFontPx: Float,
    maxFontPx: Float,
    density: androidx.compose.ui.unit.Density,
): FittedText {
    if (text.isBlank() || maxWidthPx <= 0f || maxHeightPx <= 0f) {
        val layoutResult = measureText(
            textMeasurer = textMeasurer,
            text = text,
            fontSizePx = minFontPx,
            maxWidthPx = maxWidthPx,
            maxHeightPx = maxHeightPx,
            density = density,
        )
        return FittedText(minFontPx, layoutResult)
    }

    val upperBound = max(minFontPx, maxFontPx)
    var low = minFontPx
    var high = upperBound
    var best = minFontPx

    repeat(FIT_ITERATIONS) {
        val mid = (low + high) / 2f
        val layoutResult = measureText(
            textMeasurer = textMeasurer,
            text = text,
            fontSizePx = mid,
            maxWidthPx = maxWidthPx,
            maxHeightPx = maxHeightPx,
            density = density,
        )
        val fits = !layoutResult.didOverflowWidth && !layoutResult.didOverflowHeight
        if (fits) {
            best = mid
            low = mid
        } else {
            high = mid
        }
    }

    val finalLayout = measureText(
        textMeasurer = textMeasurer,
        text = text,
        fontSizePx = best,
        maxWidthPx = maxWidthPx,
        maxHeightPx = maxHeightPx,
        density = density,
    )
    return FittedText(best, finalLayout)
}

private fun measureText(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    fontSizePx: Float,
    maxWidthPx: Float,
    maxHeightPx: Float,
    density: androidx.compose.ui.unit.Density,
): androidx.compose.ui.text.TextLayoutResult {
    val fontSizeSp = with(density) { fontSizePx.toSp() }
    return textMeasurer.measure(
        text = AnnotatedString(text),
        style = TextStyle(fontSize = fontSizeSp),
        constraints = Constraints(
            maxWidth = maxWidthPx.roundToInt(),
            maxHeight = maxHeightPx.roundToInt(),
        ),
        overflow = TextOverflow.Clip,
        softWrap = true,
        maxLines = MAX_LINES_CAP,
    )
}

private const val MIN_FONT_SP = 10
private const val MAX_FONT_SP = 28
private const val MAX_HEIGHT_MULTIPLIER = 6f
private const val MAX_HEIGHT_DP = 240
private const val MAX_LINES_CAP = 8
private const val FIT_ITERATIONS = 7
