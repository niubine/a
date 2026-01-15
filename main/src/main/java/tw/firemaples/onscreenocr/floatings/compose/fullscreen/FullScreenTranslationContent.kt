package tw.firemaples.onscreenocr.floatings.compose.fullscreen

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.firemaples.onscreenocr.floatings.compose.base.pxToDp
import tw.firemaples.onscreenocr.floatings.manager.LayoutType
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
    val translationAlpha by animateFloatAsState(
        targetValue = if (state.showOriginal) 0f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "translationAlpha",
    )
    val backgroundBitmap = if (state.showOriginal) {
        state.originalBitmap
    } else {
        state.cleanedBitmap
    }
    val transformBitmap = state.originalBitmap ?: backgroundBitmap

    LaunchedEffect(Unit) {
        val rootLocation = requestRootLocationOnScreen.invoke()
        viewModel.onRootViewPositioned(
            xOffset = rootLocation.left,
            yOffset = rootLocation.top,
        )
    }

    BoxWithConstraints(
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
        val viewWidthPx = constraints.maxWidth
        val viewHeightPx = constraints.maxHeight
        val transform = remember(
            transformBitmap,
            viewWidthPx,
            viewHeightPx,
        ) {
            resolveDisplayTransform(
                bitmap = transformBitmap,
                viewWidthPx = viewWidthPx,
                viewHeightPx = viewHeightPx,
            )
        }
        val layoutBounds = remember(viewWidthPx, viewHeightPx) {
            Rect(0, 0, viewWidthPx, viewHeightPx)
        }
        val scaledTranslatedBlocks = remember(
            state.translatedBlocks,
            state.rootOffset,
            transform,
        ) {
            scaleBlocksForDisplay(
                blocks = state.translatedBlocks,
                rootOffset = state.rootOffset,
                transform = transform,
            )
        }
        val scaledOriginalBlocks = remember(
            state.originalBlocks,
            state.rootOffset,
            transform,
        ) {
            scaleBlocksForDisplay(
                blocks = state.originalBlocks,
                rootOffset = state.rootOffset,
                transform = transform,
            )
        }

        backgroundBitmap?.let { bitmap ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scaledWidth = (bitmap.width * transform.scale).roundToInt()
                val scaledHeight = (bitmap.height * transform.scale).roundToInt()
                drawImage(
                    image = bitmap.asImageBitmap(),
                    dstOffset = IntOffset(
                        transform.offsetX.roundToInt(),
                        transform.offsetY.roundToInt(),
                    ),
                    dstSize = IntSize(scaledWidth, scaledHeight),
                )
            }
        }

        scaledTranslatedBlocks.filter { it.text.isNotBlank() }.forEach { block ->
            OverlayText(
                block = block,
                layoutBounds = layoutBounds,
                alpha = translationAlpha,
            )
        }

        if (state.isProcessing && !state.showOriginal) {
            scaledOriginalBlocks.filter { it.text.isNotBlank() }.forEach { block ->
                DebugOverlayRect(block = block)
            }
        }
    }
}

@Composable
private fun OverlayText(
    block: OverlayTextBlock,
    layoutBounds: Rect,
    alpha: Float,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val rawPaddingHorizontalPx = with(density) { 4.dp.toPx() }
    val rawPaddingVerticalPx = with(density) { 2.dp.toPx() }
    val style = block.overlayStyle
    val layoutType = style?.layoutType ?: LayoutType.Unknown
    val lineCountHint = max(1, block.lineCountHint)
    val baseHeightPx = block.boundingBox.height().toFloat()
    val baseFontPx = max(1f, (baseHeightPx / lineCountHint) / LINE_HEIGHT_MULTIPLIER)
    val desiredFontPx = max(
        baseFontPx * MIN_FONT_SCALE,
        style?.targetFontPx ?: baseFontPx,
    )
    val maskExtraPx = max(
        with(density) { MASK_EXTRA_DP.dp.toPx() },
        desiredFontPx * MASK_EXTRA_FONT_RATIO,
    )
    val screenWidthPx = layoutBounds.width().toFloat()
    val screenHeightPx = layoutBounds.height().toFloat()
    val screenMarginPx = with(density) { SCREEN_MARGIN_DP.dp.toPx() }
    val usableScreenWidthPx = max(1f, screenWidthPx - screenMarginPx * 2f)
    val usableScreenHeightPx = max(1f, screenHeightPx - screenMarginPx * 2f)
    val screenRect = layoutBounds
    val maxExpandWidthPx = min(
        usableScreenWidthPx,
        block.boundingBox.width().toFloat() * MAX_EXPAND_RATIO + maskExtraPx * 2f,
    )
    val maxExpandHeightPx = min(
        usableScreenHeightPx,
        block.boundingBox.height().toFloat() * MAX_EXPAND_RATIO + maskExtraPx * 2f,
    )
    var expandedWidthPx = min(block.boundingBox.width().toFloat() + maskExtraPx * 2f, maxExpandWidthPx)
    var expandedHeightPx = min(block.boundingBox.height().toFloat() + maskExtraPx * 2f, maxExpandHeightPx)

    fun resolvePadding(widthPx: Float, heightPx: Float): Pair<Float, Float> {
        val paddingScale = when (layoutType) {
            LayoutType.Subtitle -> 0.9f
            LayoutType.Bubble -> 1.0f
            LayoutType.Label -> 0.8f
            LayoutType.Paragraph -> 1.0f
            LayoutType.Unknown -> 1.0f
        }
        val paddingHorizontalPx =
            min(rawPaddingHorizontalPx, widthPx * PADDING_WIDTH_RATIO) * paddingScale
        val paddingVerticalPx =
            min(rawPaddingVerticalPx, heightPx * PADDING_HEIGHT_RATIO) * paddingScale
        return paddingHorizontalPx to paddingVerticalPx
    }

    repeat(EXPAND_ITERATIONS) {
        val (paddingHorizontalPx, paddingVerticalPx) = resolvePadding(expandedWidthPx, expandedHeightPx)
        val availableWidthPx = max(1f, expandedWidthPx - paddingHorizontalPx * 2f)
        val availableHeightPx = max(1f, expandedHeightPx - paddingVerticalPx * 2f)
        val layoutResult = measureText(
            textMeasurer = textMeasurer,
            text = block.text,
            fontSizePx = desiredFontPx,
            maxWidthPx = availableWidthPx,
            maxHeightPx = availableHeightPx,
            lineHeightMultiplier = LINE_HEIGHT_MULTIPLIER,
            density = density,
        )
        val needWidth = layoutResult.didOverflowWidth
        val needHeight = layoutResult.didOverflowHeight
        if (!needWidth && !needHeight) {
            return@repeat
        }
        val nextWidth = if (needWidth) min(expandedWidthPx * EXPAND_STEP, maxExpandWidthPx) else expandedWidthPx
        val nextHeight = if (needHeight) min(expandedHeightPx * EXPAND_STEP, maxExpandHeightPx) else expandedHeightPx
        if (nextWidth == expandedWidthPx && nextHeight == expandedHeightPx) {
            return@repeat
        }
        expandedWidthPx = nextWidth
        expandedHeightPx = nextHeight
    }

    val expandedRect = clampRectToBounds(
        Rect(
            (block.boundingBox.centerX() - expandedWidthPx / 2f).roundToInt(),
            (block.boundingBox.centerY() - expandedHeightPx / 2f).roundToInt(),
            (block.boundingBox.centerX() + expandedWidthPx / 2f).roundToInt(),
            (block.boundingBox.centerY() + expandedHeightPx / 2f).roundToInt(),
        ),
        screenRect,
    )

    val (paddingHorizontalPx, paddingVerticalPx) =
        resolvePadding(expandedRect.width().toFloat(), expandedRect.height().toFloat())
    val availableWidthPx = max(1f, expandedRect.width().toFloat() - paddingHorizontalPx * 2f)
    val availableHeightPx = max(1f, expandedRect.height().toFloat() - paddingVerticalPx * 2f)
    val maxFontSizeFromBoxPx =
        max(1f, (availableHeightPx / lineCountHint) / LINE_HEIGHT_MULTIPLIER)
    val cappedMaxFontPx = min(with(density) { MAX_FONT_SP.sp.toPx() }, maxFontSizeFromBoxPx)
    val minFontSizePx = max(
        with(density) { MIN_FONT_SP.sp.toPx() },
        baseFontPx,
    )
    val maxFontSizePx = max(minFontSizePx, min(desiredFontPx, cappedMaxFontPx))
    val densityValue = density.density
    val fontScale = density.fontScale
    val fittedText = remember(
        block.text,
        availableWidthPx,
        availableHeightPx,
        lineCountHint,
        maxFontSizePx,
        minFontSizePx,
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
            lineHeightMultiplier = LINE_HEIGHT_MULTIPLIER,
            density = density,
        )
    }
    val fontSizeSp = with(density) { fittedText.fontSizePx.toSp() }
    val lineHeightSp = fontSizeSp * LINE_HEIGHT_MULTIPLIER
    val maxLines = max(1, fittedText.layoutResult.lineCount)
    val shadow = style?.let { resolvedStyle ->
        resolvedStyle.shadowColorArgb?.let { color ->
            Shadow(
                color = Color(color),
                offset = Offset.Zero,
                blurRadius = with(density) { resolvedStyle.shadowRadiusDp.dp.toPx() },
            )
        }
    }
    val textStyle = TextStyle(
        fontSize = fontSizeSp,
        lineHeight = lineHeightSp,
        color = style?.fgColorArgb?.let { Color(it) } ?: Color.White,
        shadow = shadow,
    )
    val cornerRadius = when (layoutType) {
        LayoutType.Bubble -> 8.dp
        LayoutType.Label -> 6.dp
        LayoutType.Subtitle -> 4.dp
        LayoutType.Paragraph -> 4.dp
        LayoutType.Unknown -> 4.dp
    }
    val shape = RoundedCornerShape(cornerRadius)
    val backgroundColor = style?.let {
        Color(it.bgColorArgb).copy(alpha = it.bgAlpha)
    } ?: Color(0xCC000000)
    val paddingHorizontalDp = with(density) { paddingHorizontalPx.toDp() }
    val paddingVerticalDp = with(density) { paddingVerticalPx.toDp() }

    Box(
        modifier = Modifier
            .absoluteOffset(
                x = expandedRect.left.pxToDp(),
                y = expandedRect.top.pxToDp(),
            )
            .width(expandedRect.width().pxToDp())
            .height(expandedRect.height().pxToDp())
            .graphicsLayer(alpha = alpha)
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

@Composable
private fun DebugOverlayRect(
    block: OverlayTextBlock,
) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier = Modifier
            .absoluteOffset(
                x = block.boundingBox.left.pxToDp(),
                y = block.boundingBox.top.pxToDp(),
            )
            .width(block.boundingBox.width().pxToDp())
            .height(block.boundingBox.height().pxToDp())
            .border(width = 1.dp, color = DEBUG_RECT_COLOR, shape = shape)
    )
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
    lineHeightMultiplier: Float,
    density: androidx.compose.ui.unit.Density,
): FittedText {
    if (text.isBlank() || maxWidthPx <= 0f || maxHeightPx <= 0f) {
        val layoutResult = measureText(
            textMeasurer = textMeasurer,
            text = text,
            fontSizePx = minFontPx,
            maxWidthPx = maxWidthPx,
            maxHeightPx = maxHeightPx,
            lineHeightMultiplier = lineHeightMultiplier,
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
            lineHeightMultiplier = lineHeightMultiplier,
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
        lineHeightMultiplier = lineHeightMultiplier,
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
    lineHeightMultiplier: Float,
    density: androidx.compose.ui.unit.Density,
): androidx.compose.ui.text.TextLayoutResult {
    val fontSizeSp = with(density) { fontSizePx.toSp() }
    val lineHeightSp = fontSizeSp * lineHeightMultiplier
    return textMeasurer.measure(
        text = AnnotatedString(text),
        style = TextStyle(
            fontSize = fontSizeSp,
            lineHeight = lineHeightSp,
        ),
        constraints = Constraints(
            maxWidth = maxWidthPx.roundToInt(),
            maxHeight = maxHeightPx.roundToInt(),
        ),
        overflow = TextOverflow.Clip,
        softWrap = true,
        maxLines = MAX_LINES_CAP,
    )
}

private fun clampRectToBounds(rect: Rect, bounds: Rect): Rect {
    var width = rect.width()
    var height = rect.height()
    if (width > bounds.width()) {
        width = bounds.width()
    }
    if (height > bounds.height()) {
        height = bounds.height()
    }
    var left = rect.left
    var top = rect.top
    if (left < bounds.left) {
        left = bounds.left
    }
    if (left + width > bounds.right) {
        left = bounds.right - width
    }
    if (top < bounds.top) {
        top = bounds.top
    }
    if (top + height > bounds.bottom) {
        top = bounds.bottom - height
    }
    return Rect(left, top, left + width, top + height)
}

private data class DisplayTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

private fun resolveDisplayTransform(
    bitmap: android.graphics.Bitmap?,
    viewWidthPx: Int,
    viewHeightPx: Int,
): DisplayTransform {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
        return DisplayTransform(1f, 0f, 0f)
    }
    if (viewWidthPx <= 0 || viewHeightPx <= 0) {
        return DisplayTransform(1f, 0f, 0f)
    }
    val scale = viewWidthPx.toFloat() / bitmap.width.toFloat()
    return DisplayTransform(scale = scale, offsetX = 0f, offsetY = 0f)
}

private fun scaleBlocksForDisplay(
    blocks: List<OverlayTextBlock>,
    rootOffset: IntOffset,
    transform: DisplayTransform,
): List<OverlayTextBlock> {
    if (blocks.isEmpty()) {
        return emptyList()
    }
    return blocks.map { block ->
        val scaledRect = transformRect(
            rect = block.boundingBox,
            rootOffset = rootOffset,
            transform = transform,
        )
        val scaledStyle = block.overlayStyle?.let { style ->
            style.copy(targetFontPx = style.targetFontPx * transform.scale)
        }
        block.copy(
            boundingBox = scaledRect,
            overlayStyle = scaledStyle,
            fontSizeHintPx = block.fontSizeHintPx?.let { it * transform.scale },
        )
    }
}

private fun transformRect(
    rect: Rect,
    rootOffset: IntOffset,
    transform: DisplayTransform,
): Rect {
    val left = ((rect.left - rootOffset.x) * transform.scale + transform.offsetX).roundToInt()
    val top = ((rect.top - rootOffset.y) * transform.scale + transform.offsetY).roundToInt()
    val right = ((rect.right - rootOffset.x) * transform.scale + transform.offsetX).roundToInt()
    val bottom = ((rect.bottom - rootOffset.y) * transform.scale + transform.offsetY).roundToInt()
    return Rect(left, top, right, bottom)
}

private const val MIN_FONT_SP = 6
private const val MAX_FONT_SP = 48
private const val LINE_HEIGHT_MULTIPLIER = 1.15f
private const val PADDING_WIDTH_RATIO = 0.12f
private const val PADDING_HEIGHT_RATIO = 0.18f
private const val MAX_LINES_CAP = 20
private const val FIT_ITERATIONS = 7
private const val EXPAND_ITERATIONS = 6
private const val EXPAND_STEP = 1.2f
private const val MAX_EXPAND_RATIO = 2.2f
private const val MIN_FONT_SCALE = 1.1f
private const val MASK_EXTRA_DP = 2
private const val MASK_EXTRA_FONT_RATIO = 0.16f
private const val SCREEN_MARGIN_DP = 4
private val DEBUG_RECT_COLOR = Color(0xFFFF3B30)
