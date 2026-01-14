package tw.firemaples.onscreenocr.floatings.manager

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OverlayStyleExtractor(
    private val density: Float,
) {
    fun applyStyles(
        bitmap: Bitmap,
        blocks: List<OverlayTextBlock>,
        screenRect: Rect,
    ): List<OverlayTextBlock> {
        if (blocks.isEmpty() || bitmap.width <= 0 || bitmap.height <= 0) {
            return blocks
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        return blocks.map { block ->
            val style = extractStyle(
                pixels = pixels,
                bitmapWidth = width,
                bitmapHeight = height,
                block = block,
                screenRect = screenRect,
            )
            block.copy(overlayStyle = style)
        }
    }

    private fun extractStyle(
        pixels: IntArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        block: OverlayTextBlock,
        screenRect: Rect,
    ): OverlayStyle {
        val rect = clampRect(block.boundingBox, bitmapWidth, bitmapHeight)
        if (rect.width() <= 0 || rect.height() <= 0) {
            return defaultStyle(block, LayoutType.Unknown)
        }

        val marginPx = max(1, (STYLE_MARGIN_DP * density).roundToInt())
        val outerRect = Rect(
            max(0, rect.left - marginPx),
            max(0, rect.top - marginPx),
            min(bitmapWidth, rect.right + marginPx),
            min(bitmapHeight, rect.bottom + marginPx),
        )

        val ringStats = collectStats(
            pixels = pixels,
            bitmapWidth = bitmapWidth,
            rect = outerRect,
            step = SAMPLE_STEP_PX,
            skipRect = rect,
            maxSamples = MAX_SAMPLES,
        )
        val innerStats = collectStats(
            pixels = pixels,
            bitmapWidth = bitmapWidth,
            rect = rect,
            step = SAMPLE_STEP_PX,
            skipRect = null,
            maxSamples = MAX_SAMPLES,
        )
        val useInnerStats = shouldUseInnerBackground(ringStats, innerStats)
        val backgroundStats = if (useInnerStats) innerStats else ringStats

        val bgColor = backgroundStats.dominantColor()
        val bgLuma = backgroundStats.lumaMean()
        val bgVariance = backgroundStats.lumaVariance()

        val fgStats = collectForegroundStats(
            pixels = pixels,
            bitmapWidth = bitmapWidth,
            rect = rect,
            bgColor = bgColor,
        )
        val fgCandidate = if (fgStats.count > 0) fgStats.dominantColor() else null
        val fgColor = resolveForegroundColor(fgCandidate, bgColor)

        val layoutType = classifyLayoutType(
            block = block,
            screenRect = screenRect,
            bgLuma = bgLuma,
            bgVariance = bgVariance,
        )

        val bgComplex = bgVariance > COMPLEX_BG_VARIANCE
        val bgAlpha = resolveBackgroundAlpha(layoutType, bgComplex)
        val useBlurBg = false

        val baseFontPx = max(
            1f,
            (rect.height().toFloat() / max(1, block.lineCountHint)) / LINE_HEIGHT_MULTIPLIER,
        )
        val targetFontPx = baseFontPx * resolveFontScale(layoutType)

        return OverlayStyle(
            bgColorArgb = bgColor,
            fgColorArgb = fgColor,
            bgAlpha = bgAlpha,
            useBlurBg = useBlurBg,
            shadowColorArgb = null,
            shadowRadiusDp = 0f,
            targetFontPx = targetFontPx,
            layoutType = layoutType,
        )
    }

    private fun collectForegroundStats(
        pixels: IntArray,
        bitmapWidth: Int,
        rect: Rect,
        bgColor: Int,
    ): ColorStats {
        val stats = ColorStats()
        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)

        var samplesChecked = 0
        var y = rect.top
        while (y < rect.bottom && samplesChecked < MAX_SAMPLES) {
            var x = rect.left
            while (x < rect.right && samplesChecked < MAX_SAMPLES) {
                val color = pixels[(y * bitmapWidth) + x]
                val distance =
                    abs(Color.red(color) - bgR) +
                        abs(Color.green(color) - bgG) +
                        abs(Color.blue(color) - bgB)
                if (distance >= FG_DISTANCE_THRESHOLD) {
                    stats.add(color)
                }
                samplesChecked++
                x += SAMPLE_STEP_PX
            }
            y += SAMPLE_STEP_PX
        }

        return stats
    }

    private fun collectStats(
        pixels: IntArray,
        bitmapWidth: Int,
        rect: Rect,
        step: Int,
        skipRect: Rect?,
        maxSamples: Int,
    ): ColorStats {
        val stats = ColorStats()
        var count = 0
        var y = rect.top
        while (y < rect.bottom && count < maxSamples) {
            var x = rect.left
            while (x < rect.right && count < maxSamples) {
                if (skipRect == null || !skipRect.contains(x, y)) {
                    stats.add(pixels[(y * bitmapWidth) + x])
                    count = stats.count
                }
                x += step
            }
            y += step
        }
        return stats
    }

    private fun resolveForegroundColor(candidate: Int?, bgColor: Int): Int {
        if (candidate == null) {
            return pickContrastingColor(bgColor)
        }
        return if (contrastRatio(candidate, bgColor) >= MIN_CONTRAST_RATIO) {
            candidate
        } else {
            pickContrastingColor(bgColor)
        }
    }

    private fun pickContrastingColor(bgColor: Int): Int {
        return if (luminance(bgColor) > 0.5f) Color.BLACK else Color.WHITE
    }

    private fun resolveBackgroundAlpha(layoutType: LayoutType, bgComplex: Boolean): Float {
        val baseAlpha = when (layoutType) {
            LayoutType.Subtitle -> 0.6f
            LayoutType.Bubble -> 0.98f
            LayoutType.Label -> 0.95f
            LayoutType.Paragraph -> 0.85f
            LayoutType.Unknown -> 0.85f
        }
        return if (bgComplex) max(baseAlpha, COMPLEX_BG_ALPHA) else baseAlpha
    }

    private fun classifyLayoutType(
        block: OverlayTextBlock,
        screenRect: Rect,
        bgLuma: Float,
        bgVariance: Float,
    ): LayoutType {
        val screenWidth = max(1, screenRect.width()).toFloat()
        val screenHeight = max(1, screenRect.height()).toFloat()
        val box = block.boundingBox
        val widthRatio = box.width() / screenWidth
        val heightRatio = box.height() / screenHeight
        val centerYRatio = (box.centerY() - screenRect.top) / screenHeight

        return when {
            centerYRatio > 0.7f && widthRatio > 0.55f && block.lineCountHint <= 2 ->
                LayoutType.Subtitle
            bgVariance <= LOW_BG_VARIANCE && bgLuma >= LIGHT_BG_LUMA ->
                LayoutType.Bubble
            widthRatio <= LABEL_WIDTH_RATIO && heightRatio <= LABEL_HEIGHT_RATIO &&
                bgVariance <= MEDIUM_BG_VARIANCE ->
                LayoutType.Label
            block.lineCountHint >= 3 || (heightRatio >= PARAGRAPH_HEIGHT_RATIO && widthRatio <= 0.7f) ->
                LayoutType.Paragraph
            else -> LayoutType.Unknown
        }
    }

    private fun resolveFontScale(layoutType: LayoutType): Float {
        return when (layoutType) {
            LayoutType.Subtitle -> 1.05f
            LayoutType.Bubble -> 1.03f
            LayoutType.Label -> 1.08f
            LayoutType.Paragraph -> 1.0f
            LayoutType.Unknown -> 1.0f
        }
    }

    private fun shouldUseInnerBackground(ringStats: ColorStats, innerStats: ColorStats): Boolean {
        if (ringStats.count < MIN_BG_SAMPLES) {
            return true
        }
        if (innerStats.count == 0) {
            return false
        }
        val ringVariance = ringStats.lumaVariance()
        val innerVariance = innerStats.lumaVariance()
        if (ringVariance > innerVariance * RING_VARIANCE_MULTIPLIER) {
            return true
        }
        val ringColor = ringStats.dominantColor()
        val innerColor = innerStats.dominantColor()
        return colorDistance(ringColor, innerColor) > BG_COLOR_DISTANCE_THRESHOLD
    }

    private fun colorDistance(a: Int, b: Int): Int {
        return abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))
    }

    private fun clampRect(rect: Rect, width: Int, height: Int): Rect {
        return Rect(
            rect.left.coerceIn(0, width),
            rect.top.coerceIn(0, height),
            rect.right.coerceIn(0, width),
            rect.bottom.coerceIn(0, height),
        )
    }

    private fun defaultStyle(block: OverlayTextBlock, layoutType: LayoutType): OverlayStyle {
        val baseFontPx = max(
            1f,
            (block.boundingBox.height().toFloat() / max(1, block.lineCountHint)) / LINE_HEIGHT_MULTIPLIER,
        )
        val targetFontPx = baseFontPx * resolveFontScale(layoutType)
        return OverlayStyle(
            bgColorArgb = Color.BLACK,
            fgColorArgb = Color.WHITE,
            bgAlpha = 0.8f,
            useBlurBg = false,
            shadowColorArgb = null,
            shadowRadiusDp = 0f,
            targetFontPx = targetFontPx,
            layoutType = layoutType,
        )
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return (0.2126f * r) + (0.7152f * g) + (0.0722f * b)
    }

    private fun contrastRatio(fgColor: Int, bgColor: Int): Float {
        val l1 = luminance(fgColor)
        val l2 = luminance(bgColor)
        val maxLum = max(l1, l2)
        val minLum = min(l1, l2)
        return (maxLum + 0.05f) / (minLum + 0.05f)
    }

    private class ColorStats {
        private val bins = IntArray(COLOR_BINS)
        private var sumLuma = 0f
        private var sumLumaSq = 0f
        var count: Int = 0
            private set

        fun add(color: Int) {
            val r = Color.red(color) shr 4
            val g = Color.green(color) shr 4
            val b = Color.blue(color) shr 4
            val index = (r shl 8) or (g shl 4) or b
            bins[index]++
            val luma = (0.2126f * (r * 16 + 8) + 0.7152f * (g * 16 + 8) + 0.0722f * (b * 16 + 8)) / 255f
            sumLuma += luma
            sumLumaSq += (luma * luma)
            count++
        }

        fun dominantColor(): Int {
            if (count == 0) {
                return Color.BLACK
            }
            var maxCount = 0
            var maxIndex = 0
            for (i in bins.indices) {
                if (bins[i] > maxCount) {
                    maxCount = bins[i]
                    maxIndex = i
                }
            }
            val r = (maxIndex shr 8) and 0xF
            val g = (maxIndex shr 4) and 0xF
            val b = maxIndex and 0xF
            return Color.rgb(r * 16 + 8, g * 16 + 8, b * 16 + 8)
        }

        fun lumaMean(): Float {
            return if (count == 0) 0f else sumLuma / count
        }

        fun lumaVariance(): Float {
            if (count == 0) {
                return 0f
            }
            val mean = sumLuma / count
            return (sumLumaSq / count) - (mean * mean)
        }
    }

    private companion object {
        const val STYLE_MARGIN_DP = 3f
        const val SAMPLE_STEP_PX = 2
        const val MAX_SAMPLES = 1400
        const val MIN_BG_SAMPLES = 32
        const val FG_DISTANCE_THRESHOLD = 60
        const val COMPLEX_BG_VARIANCE = 0.02f
        const val LOW_BG_VARIANCE = 0.01f
        const val MEDIUM_BG_VARIANCE = 0.02f
        const val LIGHT_BG_LUMA = 0.72f
        const val LABEL_WIDTH_RATIO = 0.3f
        const val LABEL_HEIGHT_RATIO = 0.12f
        const val PARAGRAPH_HEIGHT_RATIO = 0.2f
        const val MIN_CONTRAST_RATIO = 4.5f
        const val COMPLEX_BG_ALPHA = 0.85f
        const val RING_VARIANCE_MULTIPLIER = 1.6f
        const val BG_COLOR_DISTANCE_THRESHOLD = 72
        const val LINE_HEIGHT_MULTIPLIER = 1.15f
        const val COLOR_BINS = 16 * 16 * 16
    }
}
