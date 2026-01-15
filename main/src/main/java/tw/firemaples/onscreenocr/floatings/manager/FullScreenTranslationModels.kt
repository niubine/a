package tw.firemaples.onscreenocr.floatings.manager

import android.graphics.Bitmap
import android.graphics.Rect
import tw.firemaples.onscreenocr.translator.TranslationProviderType

data class OverlayTextBlock(
    val text: String,
    val boundingBox: Rect,
    val lineCountHint: Int = 1,
    val source: OverlayTextSource = OverlayTextSource.Unknown,
    val overlayStyle: OverlayStyle? = null,
    val fontSizeHintPx: Float? = null,
)

data class OverlayStyle(
    val bgColorArgb: Int,
    val fgColorArgb: Int,
    val bgAlpha: Float,
    val useBlurBg: Boolean,
    val shadowColorArgb: Int? = null,
    val shadowRadiusDp: Float = 0f,
    val targetFontPx: Float,
    val layoutType: LayoutType,
)

enum class LayoutType {
    Subtitle,
    Bubble,
    Label,
    Paragraph,
    Unknown,
}

enum class OverlayTextSource {
    Accessibility,
    Ocr,
    Fallback,
    Mixed,
    Unknown,
}

data class FullScreenTranslationResult(
    val originalBlocks: List<OverlayTextBlock>,
    val translatedBlocks: List<OverlayTextBlock>,
    val providerType: TranslationProviderType,
    val cleanedBitmap: Bitmap? = null,
)
