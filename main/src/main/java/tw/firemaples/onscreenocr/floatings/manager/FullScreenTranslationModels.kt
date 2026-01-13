package tw.firemaples.onscreenocr.floatings.manager

import android.graphics.Rect
import tw.firemaples.onscreenocr.translator.TranslationProviderType

data class OverlayTextBlock(
    val text: String,
    val boundingBox: Rect,
)

data class FullScreenTranslationResult(
    val originalBlocks: List<OverlayTextBlock>,
    val translatedBlocks: List<OverlayTextBlock>,
    val providerType: TranslationProviderType,
)
