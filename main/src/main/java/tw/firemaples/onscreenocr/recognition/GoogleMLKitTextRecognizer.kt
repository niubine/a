package tw.firemaples.onscreenocr.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.Text.TextBlock
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import tw.firemaples.onscreenocr.R
import tw.firemaples.onscreenocr.log.FirebaseEvent
import tw.firemaples.onscreenocr.pages.setting.SettingManager
import tw.firemaples.onscreenocr.utils.Utils
import kotlin.math.max
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class GoogleMLKitTextRecognizer : TextRecognizer {
    companion object {
        private val devanagariLangCodes = arrayOf("hi", "mr", "ne", "sa")
        private const val SEGMENT_GAP_HEIGHT_RATIO = 0.65f
        private const val SEGMENT_GAP_WIDTH_RATIO = 1.6f
        private const val SEGMENT_GAP_HEIGHT_RATIO_TIGHT = 0.55f
        private const val SEGMENT_GAP_WIDTH_RATIO_TIGHT = 1.3f
        private const val SEGMENT_GAP_MIN_PX = 2
        private const val SEGMENT_AGGRESSIVE_MIN_ELEMENTS = 3
        private const val SEGMENT_FONT_HEIGHT_MULTIPLIER = 1.15f

        fun getSupportedLanguageList(context: Context): List<RecognitionLanguage> {
            val res = context.resources
            val langCodes = res.getStringArray(R.array.lang_ocr_google_mlkit_code_bcp_47)
            val langNames = res.getStringArray(R.array.lang_ocr_google_mlkit_name)

            val result = mutableListOf<RecognitionLanguage>()
            langNames.forEachIndexed { i, name ->
                if (name.startsWith("old ", ignoreCase = true) ||
                    name.startsWith("middle ", ignoreCase = true)
                ) return@forEachIndexed

                val code = langCodes[i]
                val item = RecognitionLanguage(
                    code = code,
                    displayName = name,
                    selected = false,
                    downloaded = true,
                    recognizer = TextRecognitionProviderType.GoogleMLKit,
                    innerCode = code,
                )
                result.add(item)
                if (code == "ja" || code.startsWith("zh")) {
                    val itemRTL = item.copy(
                        code = item.code + ":RTL",
                        displayName = item.displayName + "(RTL)",
                    )
                    result.add(itemRTL)
                }
            }

            return result
                .distinctBy { it.displayName }
                .sortedBy { it.code }
        }
    }

    private val context: Context by lazy { Utils.context }

    override val type: TextRecognitionProviderType
        get() = TextRecognitionProviderType.GoogleMLKit

    override val name: String
        get() = type.name

    private val recognizerMap =
        mutableMapOf<ScriptType, com.google.mlkit.vision.text.TextRecognizer>()

    override suspend fun recognize(lang: RecognitionLanguage, bitmap: Bitmap): RecognitionResult {
        val langInfo = lang.code.split(":")
        val targetLang = langInfo[0]
        val rtl = langInfo.getOrNull(1) == "RTL"
        return doRecognize(bitmap, targetLang, rtl)
    }

    private suspend fun doRecognize(bitmap: Bitmap, lang: String, rtl: Boolean): RecognitionResult =
        suspendCoroutine {
            val script = getScriptType(lang)

            val recognizer = recognizerMap.getOrPut(script) {
                FirebaseEvent.logStartOCRInitializing(name)

                val options = when (script) {
                    ScriptType.Chinese -> ChineseTextRecognizerOptions.Builder().build()
                    ScriptType.Devanagari -> DevanagariTextRecognizerOptions.Builder().build()
                    ScriptType.Japanese -> JapaneseTextRecognizerOptions.Builder().build()
                    ScriptType.Korean -> KoreanTextRecognizerOptions.Builder().build()
                    ScriptType.Latin -> TextRecognizerOptions.DEFAULT_OPTIONS
                }

                TextRecognition.getClient(options).also {
                    FirebaseEvent.logOCRInitialized(name)
                }
            }

            val image = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val joiner = SettingManager.textBlockJoiner.joiner
                    val segmentedBlocks = buildSegmentedTextBlocks(
                        result = result,
                        rtl = rtl,
                    )
                    val textBlocks = segmentedBlocks.ifEmpty {
                        buildBlockTextBlocks(result, rtl)
                    }
                    val text = if (textBlocks.isNotEmpty()) {
                        textBlocks.joinToString(separator = joiner) { block -> block.text }
                    } else {
                        formatBlockText(if (rtl) result.text.reversed() else result.text)
                    }

                    it.resume(
                        RecognitionResult(
                            langCode = lang.toISO639(),
                            result = text,
                            boundingBoxes = textBlocks.map { block -> block.boundingBox },
                            textBlocks = textBlocks,
                        ),
                    )
                }
                .addOnFailureListener { e ->
                    it.resumeWithException(e)
                }
        }

    private fun TextBlock.getText(rtl: Boolean): String {
        return if (rtl) {
            lines.joinToString(separator = "\n") { line ->
                line.text.reversed()
            }
        } else {
            this.text
        }
    }

    private fun formatBlockText(text: String): String {
        val withDashRemoved = if (SettingManager.removeEndDash) {
            text.replace("-\n", "")
                .replace("-$".toRegex(), "")
        } else {
            text
        }

        return if (SettingManager.removeLineBreakersInBlock) {
            withDashRemoved.replace("\n ", " ")
                .replace("\n", " ")
        } else {
            withDashRemoved
        }
    }

    private data class ElementInfo(
        val text: String,
        val boundingBox: Rect,
    )

    private fun buildSegmentedTextBlocks(
        result: Text,
        rtl: Boolean,
    ): List<RecognizedTextBlock> {
        val segments = mutableListOf<RecognizedTextBlock>()
        for (block in result.textBlocks) {
            if (block.lines.isEmpty()) {
                val fallbackText = formatBlockText(block.getText(rtl))
                val fallbackBox = block.boundingBox ?: continue
                if (fallbackText.isNotBlank()) {
                    segments.add(
                        RecognizedTextBlock(
                            text = fallbackText,
                            boundingBox = fallbackBox,
                            lineCount = 1,
                        )
                    )
                }
                continue
            }

            for (line in block.lines) {
                val elements = line.elements.mapNotNull { element ->
                    val rect = element.boundingBox ?: return@mapNotNull null
                    ElementInfo(
                        text = element.text,
                        boundingBox = rect,
                    )
                }
                if (elements.isEmpty()) {
                    val lineBox = line.boundingBox ?: block.boundingBox ?: continue
                    val lineText = if (rtl) line.text.reversed() else line.text
                    val formatted = formatBlockText(lineText)
                    if (formatted.isNotBlank()) {
                        segments.add(
                            RecognizedTextBlock(
                                text = formatted,
                                boundingBox = lineBox,
                                lineCount = 1,
                            )
                        )
                    }
                    continue
                }

                val sorted = elements.sortedBy { it.boundingBox.left }
                val heights = sorted.map { it.boundingBox.height().toFloat() }.filter { it > 0f }
                val widths = sorted.map { element ->
                    val length = element.text.length.coerceAtLeast(1)
                    element.boundingBox.width().toFloat() / length.toFloat()
                }.filter { it > 0f }
                val medianH = median(heights)
                val medianW = median(widths)
                val gapThreshold = resolveSegmentGapThreshold(sorted.size, medianH, medianW)
                val joiner = resolveElementJoiner(line.text)

                val buffer = mutableListOf<ElementInfo>()
                buffer.add(sorted.first())
                for (next in sorted.drop(1)) {
                    val previous = buffer.last()
                    val gap = max(0, next.boundingBox.left - previous.boundingBox.right)
                    if (gap.toFloat() >= gapThreshold) {
                        addSegment(buffer, joiner, rtl, segments)
                        buffer.clear()
                    }
                    buffer.add(next)
                }
                addSegment(buffer, joiner, rtl, segments)
            }
        }
        return segments
    }

    private fun buildBlockTextBlocks(
        result: Text,
        rtl: Boolean,
    ): List<RecognizedTextBlock> {
        return result.textBlocks.mapNotNull { block ->
            val boundingBox = block.boundingBox ?: return@mapNotNull null
            val text = formatBlockText(block.getText(rtl))
            if (text.isBlank()) {
                return@mapNotNull null
            }
            RecognizedTextBlock(
                text = text,
                boundingBox = boundingBox,
                lineCount = block.lines.size.coerceAtLeast(1),
            )
        }
    }

    private fun addSegment(
        elements: List<ElementInfo>,
        joiner: String,
        rtl: Boolean,
        output: MutableList<RecognizedTextBlock>,
    ) {
        if (elements.isEmpty()) {
            return
        }
        val rect = Rect(elements.first().boundingBox)
        val text = elements.joinToString(separator = joiner) { it.text }
        val normalized = formatBlockText(if (rtl) text.reversed() else text)
        if (normalized.isBlank()) {
            return
        }
        val fontSizeHintPx = resolveFontSizeHint(elements)
        elements.drop(1).forEach { rect.union(it.boundingBox) }
        output.add(
            RecognizedTextBlock(
                text = normalized,
                boundingBox = rect,
                lineCount = 1,
                fontSizeHintPx = fontSizeHintPx,
            )
        )
    }

    private fun resolveFontSizeHint(elements: List<ElementInfo>): Float? {
        val heights = elements.map { it.boundingBox.height().toFloat() }.filter { it > 0f }
        if (heights.isEmpty()) {
            return null
        }
        val medianHeight = median(heights)
        if (medianHeight <= 0f) {
            return null
        }
        return medianHeight / SEGMENT_FONT_HEIGHT_MULTIPLIER
    }

    private fun resolveSegmentGapThreshold(
        elementCount: Int,
        medianH: Float,
        medianW: Float,
    ): Float {
        val heightRatio =
            if (elementCount >= SEGMENT_AGGRESSIVE_MIN_ELEMENTS) SEGMENT_GAP_HEIGHT_RATIO_TIGHT
            else SEGMENT_GAP_HEIGHT_RATIO
        val widthRatio =
            if (elementCount >= SEGMENT_AGGRESSIVE_MIN_ELEMENTS) SEGMENT_GAP_WIDTH_RATIO_TIGHT
            else SEGMENT_GAP_WIDTH_RATIO
        val heightThreshold = medianH * heightRatio
        val widthThreshold = medianW * widthRatio
        return max(SEGMENT_GAP_MIN_PX.toFloat(), max(heightThreshold, widthThreshold))
    }

    private fun resolveElementJoiner(lineText: String): String {
        return if (lineText.contains(' ')) " " else ""
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) {
            return 0f
        }
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    override suspend fun parseToDisplayLangCode(langCode: String): String = langCode.toISO639()

//    override suspend fun supportedLanguages(): List<RecognitionLanguage> {
//        return getSupportedLanguageList()
//    }
//
//    private fun getSupportedLanguageList(): List<RecognitionLanguage> {
//        val res = context.resources
//        val langCodes = res.getStringArray(R.array.lang_ocr_google_mlkit_code_bcp_47)
//        val langNames = res.getStringArray(R.array.lang_ocr_google_mlkit_name)
//        val selected = AppPref.selectedOCRLang.let {
//            if (langCodes.contains(it)) it else Constants.DEFAULT_OCR_LANG
//        }
//
//        return langCodes.indices
//            .map { i ->
//                val code = langCodes[i]
//                RecognitionLanguage(
//                    code = code,
//                    displayName = langNames[i],
//                    selected = code == selected,
//                    downloaded = true,
//                    recognizer = Recognizer.GoogleMLKit,
//                    innerCode = code,
//                )
//            }
//            .sortedBy { it.displayName }
//            .distinctBy { it.displayName }
//            .filterNot {
//                it.displayName.startsWith("old ", ignoreCase = true) ||
//                        it.displayName.startsWith("middle ", ignoreCase = true)
//            }
//    }

//    private fun getSupportedScriptList(): List<RecognitionLanguage> {
//        val res = context.resources
//        val scriptCodes = res.getStringArray(R.array.google_MLKit_translationScriptCode)
//        val scriptNames = res.getStringArray(R.array.google_MLKit_translationScriptName)
//        val selected = AppPref.selectedOCRLang.let {
//            if (scriptCodes.contains(it)) it else scriptCodes[0]
//        }
//
//        return scriptCodes.indices.map { i ->
//            val code = scriptCodes[i]
//            RecognitionLanguage(
//                code = code,
//                displayName = scriptNames[i],
//                selected = code == selected,
//                downloaded = true,
//                recognizer = Recognizer.GoogleMLKit,
//                innerCode = code,
//            )
//        }
//    }

    private fun getScriptType(lang: String): ScriptType =
        when {
            ScriptType.Japanese.isJapanese(lang) -> ScriptType.Japanese
            ScriptType.Korean.isKorean(lang) -> ScriptType.Korean
            ScriptType.Chinese.isChinese(lang) -> ScriptType.Chinese
            ScriptType.Devanagari.isDevanagari(lang) -> ScriptType.Devanagari
            else -> ScriptType.Latin
        }

    private sealed class ScriptType {
        object Latin : ScriptType()
        object Chinese : ScriptType() {
            fun isChinese(lang: String): Boolean = lang.startsWith("zh")
        }

        object Devanagari : ScriptType() {
            fun isDevanagari(lang: String): Boolean = devanagariLangCodes.contains(lang)
        }

        object Japanese : ScriptType() {
            fun isJapanese(lang: String): Boolean = lang == "ja"
        }

        object Korean : ScriptType() {
            fun isKorean(lang: String): Boolean = lang == "ko"
        }
    }

    private fun String.toISO639(): String = split("-")[0]
}
