package tw.firemaples.onscreenocr.floatings.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.firemaples.onscreenocr.R
import tw.firemaples.onscreenocr.accessibility.TextAccessibilityService
import tw.firemaples.onscreenocr.di.MainCoroutineScope
import tw.firemaples.onscreenocr.floatings.manager.StateOperator.Companion.SCREENSHOT_DELAY
import tw.firemaples.onscreenocr.log.FirebaseEvent
import tw.firemaples.onscreenocr.pages.setting.SettingManager
import tw.firemaples.onscreenocr.recognition.RecognitionResult
import tw.firemaples.onscreenocr.recognition.RecognizedTextBlock
import tw.firemaples.onscreenocr.recognition.TextRecognitionProviderType
import tw.firemaples.onscreenocr.recognition.TextRecognizer
import tw.firemaples.onscreenocr.screenshot.ScreenExtractor
import tw.firemaples.onscreenocr.translator.TranslationProviderType
import tw.firemaples.onscreenocr.translator.TranslationResult
import tw.firemaples.onscreenocr.translator.Translator
import tw.firemaples.onscreenocr.translator.azure.MicrosoftAzureTranslator
import tw.firemaples.onscreenocr.translator.deepl.DeeplTranslator
import tw.firemaples.onscreenocr.translator.deepl.DeeplTranslatorAPI
import tw.firemaples.onscreenocr.utils.Constants
import tw.firemaples.onscreenocr.utils.Logger
import tw.firemaples.onscreenocr.utils.UIUtils
import tw.firemaples.onscreenocr.utils.firstPart
import tw.firemaples.onscreenocr.utils.setReusable
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

interface StateOperator {
    val action: SharedFlow<StateOperatorAction>

    companion object {
        const val SCREENSHOT_DELAY = 200L
    }
}

@Singleton
class StateOperatorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateNavigator: StateNavigator,
    @MainCoroutineScope
    private val scope: CoroutineScope,
) : StateOperator {
    private val logger: Logger by lazy { Logger(this::class) }

    override val action = MutableSharedFlow<StateOperatorAction>()

    private val overlayStyleExtractor = OverlayStyleExtractor(
        context.resources.displayMetrics.density
    )

    private val translationCache = object :
        LinkedHashMap<String, String>(MAX_TRANSLATION_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_TRANSLATION_CACHE_SIZE
        }
    }

    private val currentNavState: NavState
        get() = stateNavigator.currentNavState.value

    init {
        stateNavigator.navigationAction
            .onEach { action ->
                logger.debug("Receive navigationAction: $action")
                when (action) {
                    NavigationAction.NavigateToScreenCircling ->
                        startScreenCircling()

                    is NavigationAction.NavigateToScreenCircled ->
                        onAreaSelected(
                            parentRect = action.parentRect,
                            selectedRect = action.selectedRect,
                        )

                    NavigationAction.CancelScreenCircling ->
                        cancelScreenCircling()

                    is NavigationAction.NavigateToScreenCapturing ->
                        startScreenCapturing(
                            ocrLang = action.ocrLang,
                            ocrProvider = action.ocrProvider,
                        )

                    is NavigationAction.NavigateToFullScreenCapturing ->
                        startFullScreenCapturing(
                            ocrLang = action.ocrLang,
                            ocrProvider = action.ocrProvider,
                        )

                    is NavigationAction.NavigateToFullScreenTranslation ->
                        startFullScreenTranslation(
                            ocrLang = action.ocrLang,
                            ocrProvider = action.ocrProvider,
                        )

                    is NavigationAction.ReStartTranslation -> {
                        startTranslation(
                            croppedBitmap = action.croppedBitmap,
                            parentRect = action.parentRect,
                            selectedRect = action.selectedRect,
                            recognitionResult = action.recognitionResult,
                        )
                    }

                    is NavigationAction.NavigateToIdle ->
                        backToIdle()

                    is NavigationAction.NavigateToTextRecognition ->
                        startRecognition(
                            ocrLang = action.ocrLang,
                            ocrProvider = action.ocrProvider,
                            croppedBitmap = action.croppedBitmap,
                            parentRect = action.parentRect,
                            selectedRect = action.selectedRect,
                        )

                    is NavigationAction.NavigateToStartTranslation ->
                        startTranslation(
                            croppedBitmap = action.croppedBitmap,
                            parentRect = action.parentRect,
                            selectedRect = action.selectedRect,
                            recognitionResult = action.recognitionResult,
                        )

                    is NavigationAction.NavigateToTranslated ->
                        onTranslated(
                            croppedBitmap = action.croppedBitmap,
                            parentRect = action.parentRect,
                            selectedRect = action.selectedRect,
                            recognitionResult = action.recognitionResult,
                            translator = action.translator,
                            translationResult = action.translationResult,
                        )

                    is NavigationAction.ShowError ->
                        showError(action.error)
                }
            }.launchIn(scope)
    }

    private fun startScreenCircling() = scope.launch {
        if (!Translator.getTranslator().checkResources(scope)) {
            return@launch
        }

        logger.debug("startScreenCircling()")
        stateNavigator.updateState(NavState.ScreenCircling)
        FirebaseEvent.logStartAreaSelection()

        action.emit(StateOperatorAction.ShowScreenCirclingView)
        action.emit(StateOperatorAction.TopMainBar)
    }

    private fun onAreaSelected(parentRect: Rect, selectedRect: Rect) = scope.launch {
        logger.debug(
            "onAreaSelected(), parentRect: $parentRect, " +
                    "selectedRect: $selectedRect," +
                    "selectedSize: ${selectedRect.width()}x${selectedRect.height()}"
        )

        stateNavigator.updateState(
            NavState.ScreenCircled(
                parentRect = parentRect, selectedRect = selectedRect,
            )
        )
    }

    private fun cancelScreenCircling() = scope.launch {
        logger.debug("cancelScreenCircling()")
        stateNavigator.updateState(NavState.Idle)
        action.emit(StateOperatorAction.HideScreenCirclingView)
    }

    private fun startScreenCapturing(
        ocrLang: String,
        ocrProvider: TextRecognitionProviderType,
    ) = scope.launch {
        if (!Translator.getTranslator().checkResources(scope)) {
            return@launch
        }

        val state = currentNavState
        if (state !is NavState.ScreenCircled) {
            val error = "State should be ScreenCircled but $state"
            logger.error(t = IllegalStateException(error))
            showError(error)
            return@launch
        }
        val parentRect = state.parentRect
        val selectedRect = state.selectedRect
        logger.debug(
            "startScreenCapturing(), " +
                    "parentRect: $parentRect, selectedRect: $selectedRect"
        )

        stateNavigator.updateState(NavState.ScreenCapturing)

        action.emit(StateOperatorAction.HideScreenCirclingView)

        delay(SCREENSHOT_DELAY)

        var bitmap: Bitmap? = null
        try {
            FirebaseEvent.logStartCaptureScreen()
            val croppedBitmap = ScreenExtractor.extractBitmapFromScreen(
                parentRect = parentRect,
                cropRect = selectedRect,
            ).also {
                bitmap = it
            }
            FirebaseEvent.logCaptureScreenFinished()

            stateNavigator.navigate(
                NavigationAction.NavigateToTextRecognition(
                    ocrLang = ocrLang,
                    ocrProvider = ocrProvider,
                    croppedBitmap = croppedBitmap,
                    parentRect = parentRect,
                    selectedRect = selectedRect,
                )
            )
        } catch (t: TimeoutCancellationException) {
            logger.debug(t = t)
            FirebaseEvent.logCaptureScreenFailed(t)
            showError(context.getString(R.string.error_capture_screen_timeout))
            bitmap?.setReusable()
        } catch (t: Throwable) {
            logger.debug(t = t)
            FirebaseEvent.logCaptureScreenFailed(t)
            val errorMsg =
                t.message ?: context.getString(R.string.error_unknown_error_capturing_screen)
            showError(errorMsg)
            bitmap?.setReusable()
        }
    }

    private fun startRecognition(
        ocrLang: String,
        ocrProvider: TextRecognitionProviderType,
        croppedBitmap: Bitmap,
        parentRect: Rect,
        selectedRect: Rect,
    ) = scope.launch {
        stateNavigator.updateState(
            NavState.TextRecognizing(
                parentRect = parentRect,
                selectedRect = selectedRect,
                croppedBitmap = croppedBitmap,
            )
        )

        try {
            action.emit(StateOperatorAction.ShowResultView)

            val recognizer = TextRecognizer.getRecognizer(ocrProvider)
            val language = TextRecognizer.getLanguage(ocrLang, ocrProvider)!!

            FirebaseEvent.logStartOCR(recognizer.name)
            var result = withContext(Dispatchers.Default) {
                recognizer.recognize(
                    lang = language,
                    bitmap = croppedBitmap,
                )
            }
            logger.debug("On text recognized: $result")

            if (SettingManager.removeSpacesInCJK) {
                val cjkLang = arrayOf("zh", "ja", "ko")
                if (cjkLang.contains(ocrLang.split("-").getOrNull(0))) {
                    result = result.copy(
                        result = result.result.replace(" ", "")
                    )
                }
                logger.debug("Remove CJK spaces: $result")
            }

            FirebaseEvent.logOCRFinished(recognizer.name)

            stateNavigator.navigate(
                NavigationAction.NavigateToStartTranslation(
                    croppedBitmap = croppedBitmap,
                    parentRect = parentRect,
                    selectedRect = selectedRect,
                    recognitionResult = result,
                )
            )
        } catch (e: Exception) {
            val error =
                if (e.message?.contains(Constants.errorInputImageIsTooSmall) == true) {
                    context.getString(R.string.error_selected_area_too_small)
                } else
                    e.message
                        ?: context.getString(R.string.error_an_unknown_error_found_while_recognition_text)

            logger.warn(t = e)
            showError(error)
            FirebaseEvent.logOCRFailed(
                TextRecognizer.getRecognizer(ocrProvider).name, e
            )
        }
    }

    private fun startFullScreenCapturing(
        ocrLang: String,
        ocrProvider: TextRecognitionProviderType,
    ) = scope.launch {
        if (!Translator.getTranslator().checkResources(scope)) {
            return@launch
        }

        val screenRect = createFullScreenRect()
        stateNavigator.updateState(
            NavState.ScreenCircled(
                parentRect = screenRect,
                selectedRect = screenRect,
            )
        )
        startScreenCapturing(
            ocrLang = ocrLang,
            ocrProvider = ocrProvider,
        )
    }

    private fun startFullScreenTranslation(
        ocrLang: String,
        ocrProvider: TextRecognitionProviderType,
    ) = scope.launch {
        val translator = Translator.getTranslator()
        if (!translator.checkResources(scope)) {
            return@launch
        }

        if (translator.type.nonTranslation) {
            showError(context.getString(R.string.error_full_screen_translation_not_supported))
            return@launch
        }

        val screenRect = createFullScreenRect()
        stateNavigator.updateState(NavState.FullScreenCapturing)
        action.emit(StateOperatorAction.ShowFullScreenTranslationView)

        delay(SCREENSHOT_DELAY)

        var bitmap: Bitmap? = null
        try {
            FirebaseEvent.logStartCaptureScreen()
            val fullBitmap = ScreenExtractor.extractBitmapFromScreen(
                parentRect = screenRect,
                cropRect = screenRect,
            ).also {
                bitmap = it
            }
            FirebaseEvent.logCaptureScreenFinished()

            startFullScreenRecognition(
                ocrLang = ocrLang,
                ocrProvider = ocrProvider,
                fullBitmap = fullBitmap,
                screenRect = screenRect,
            )
        } catch (t: TimeoutCancellationException) {
            logger.debug(t = t)
            FirebaseEvent.logCaptureScreenFailed(t)
            showError(context.getString(R.string.error_capture_screen_timeout))
            bitmap?.setReusable()
        } catch (t: Throwable) {
            logger.debug(t = t)
            FirebaseEvent.logCaptureScreenFailed(t)
            val errorMsg =
                t.message ?: context.getString(R.string.error_unknown_error_capturing_screen)
            showError(errorMsg)
            bitmap?.setReusable()
        }
    }

    private fun startFullScreenRecognition(
        ocrLang: String,
        ocrProvider: TextRecognitionProviderType,
        fullBitmap: Bitmap,
        screenRect: Rect,
    ) = scope.launch {
        stateNavigator.updateState(
            NavState.FullScreenTextRecognizing(
                parentRect = screenRect,
                selectedRect = screenRect,
                croppedBitmap = fullBitmap,
            )
        )

        try {
            val recognizer = TextRecognizer.getRecognizer(ocrProvider)
            val language = TextRecognizer.getLanguage(ocrLang, ocrProvider)!!
            val accessibilityDeferred = async(Dispatchers.Default) {
                TextAccessibilityService.snapshotTextBlocks(context.packageName)
            }
            val scaled = scaleBitmapForOcr(fullBitmap)

            try {
                FirebaseEvent.logStartOCR(recognizer.name)
                var result = withContext(Dispatchers.Default) {
                    recognizer.recognize(
                        lang = language,
                        bitmap = scaled.bitmap,
                    )
                }
                if (scaled.isScaled) {
                    result = scaleRecognitionResult(result, scaled.scaleX, scaled.scaleY)
                }
                logger.debug("On text recognized (full screen): $result")

                if (SettingManager.removeSpacesInCJK) {
                    val cjkLang = arrayOf("zh", "ja", "ko")
                    if (cjkLang.contains(ocrLang.split("-").getOrNull(0))) {
                        result = result.copy(
                            result = result.result.replace(" ", "")
                        )
                    }
                    logger.debug("Remove CJK spaces: $result")
                }

                FirebaseEvent.logOCRFinished(recognizer.name)

                val accessibilityBlocks = accessibilityDeferred.await()

                startFullScreenTranslation(
                    screenRect = screenRect,
                    fullBitmap = fullBitmap,
                    recognitionResult = result,
                    accessibilityBlocks = accessibilityBlocks,
                )
            } finally {
                if (scaled.isScaled) {
                    scaled.bitmap.setReusable()
                }
            }
        } catch (e: Exception) {
            val error =
                if (e.message?.contains(Constants.errorInputImageIsTooSmall) == true) {
                    context.getString(R.string.error_selected_area_too_small)
                } else
                    e.message
                        ?: context.getString(R.string.error_an_unknown_error_found_while_recognition_text)

            logger.warn(t = e)
            showError(error)
            FirebaseEvent.logOCRFailed(
                TextRecognizer.getRecognizer(ocrProvider).name, e
            )
        }
    }

    private fun startFullScreenTranslation(
        screenRect: Rect,
        fullBitmap: Bitmap,
        recognitionResult: RecognitionResult,
        accessibilityBlocks: List<OverlayTextBlock>,
    ) = scope.launch {
        try {
            val translator = Translator.getTranslator()
            val ocrBlocks = buildOverlayBlocksFromRecognition(
                recognitionResult = recognitionResult,
                fallbackRect = screenRect,
                includeFallback = accessibilityBlocks.isEmpty(),
            )
            val originalBlocks = mergeTextBlocks(
                accessibilityBlocks = accessibilityBlocks,
                ocrBlocks = ocrBlocks,
                bitmap = fullBitmap,
            )
            val styledOriginalBlocks = withContext(Dispatchers.Default) {
                overlayStyleExtractor.applyStyles(
                    bitmap = fullBitmap,
                    blocks = originalBlocks,
                    screenRect = screenRect,
                )
            }
            val cleanBitmapDeferred = async(Dispatchers.Default) {
                FullScreenBitmapCleaner.cleanBitmap(
                    bitmap = fullBitmap,
                    blocks = styledOriginalBlocks,
                )
            }

            stateNavigator.updateState(
                NavState.FullScreenTextTranslating(
                    parentRect = screenRect,
                    selectedRect = screenRect,
                    croppedBitmap = fullBitmap,
                    recognitionResult = recognitionResult,
                    translationProviderType = translator.type,
                    originalBlocks = styledOriginalBlocks,
                )
            )

            FirebaseEvent.logStartTranslationText(
                text = recognitionResult.result,
                fromLang = recognitionResult.langCode,
                translator = translator,
            )

            val translatedBlocks = when (
                val translationResult = translateBlocks(
                    translator = translator,
                    sourceLangCode = recognitionResult.langCode,
                    originalBlocks = styledOriginalBlocks,
                    fallbackRect = screenRect,
                )
            ) {
                is BlockTranslationResult.Success -> translationResult.translatedBlocks

                is BlockTranslationResult.SourceLangNotSupport -> {
                    cleanBitmapDeferred.cancel()
                    FirebaseEvent.logTranslationSourceLangNotSupport(
                        translator, recognitionResult.langCode,
                    )
                    showError(context.getString(R.string.msg_translator_provider_does_not_support_the_ocr_lang))
                    return@launch
                }

                is BlockTranslationResult.Failed -> {
                    cleanBitmapDeferred.cancel()
                    FirebaseEvent.logTranslationTextFailed(translator)
                    val error = translationResult.error
                    if (error is MicrosoftAzureTranslator.Error) {
                        FirebaseEvent.logMicrosoftTranslationError(error)
                    }
                    if (error is IOException) {
                        showError(context.getString(R.string.error_can_not_connect_to_translation_server))
                    } else {
                        FirebaseEvent.logException(error)
                        showError(
                            error.localizedMessage
                                ?: context.getString(R.string.error_unknown)
                        )
                    }
                    return@launch
                }
            }

            val cleanedBitmap = runCatching { cleanBitmapDeferred.await() }
                .onFailure { logger.warn(t = it) }
                .getOrNull()
            val styledTranslatedBlocks = withContext(Dispatchers.Default) {
                overlayStyleExtractor.applyStyles(
                    bitmap = cleanedBitmap ?: fullBitmap,
                    blocks = translatedBlocks,
                    screenRect = screenRect,
                )
            }

            FirebaseEvent.logTranslationTextFinished(translator)

            stateNavigator.updateState(
                NavState.FullScreenTextTranslated(
                    parentRect = screenRect,
                    selectedRect = screenRect,
                    croppedBitmap = fullBitmap,
                    recognitionResult = recognitionResult,
                    result = FullScreenTranslationResult(
                        originalBlocks = styledOriginalBlocks,
                        translatedBlocks = styledTranslatedBlocks,
                        providerType = translator.type,
                        cleanedBitmap = cleanedBitmap,
                    ),
                )
            )
        } catch (e: Exception) {
            logger.warn(t = e)
            FirebaseEvent.logException(e)
            showError(e.message ?: "Unknown error found while translating")
        }
    }

    private data class ScaledBitmap(
        val bitmap: Bitmap,
        val scaleX: Float,
        val scaleY: Float,
        val isScaled: Boolean,
    )

    private fun scaleBitmapForOcr(bitmap: Bitmap): ScaledBitmap {
        if (bitmap.width <= MAX_OCR_WIDTH_PX) {
            return ScaledBitmap(bitmap, 1f, 1f, false)
        }

        val scale = MAX_OCR_WIDTH_PX.toFloat() / bitmap.width.toFloat()
        val targetWidth = MAX_OCR_WIDTH_PX
        val targetHeight = max(1, (bitmap.height * scale).roundToInt())
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val scaleX = bitmap.width.toFloat() / targetWidth.toFloat()
        val scaleY = bitmap.height.toFloat() / targetHeight.toFloat()
        return ScaledBitmap(scaledBitmap, scaleX, scaleY, true)
    }

    private fun scaleRecognitionResult(
        result: RecognitionResult,
        scaleX: Float,
        scaleY: Float,
    ): RecognitionResult {
        if (scaleX == 1f && scaleY == 1f) {
            return result
        }

        return result.copy(
            boundingBoxes = result.boundingBoxes.map { rect ->
                scaleRect(rect, scaleX, scaleY)
            },
            textBlocks = result.textBlocks.map { block ->
                RecognizedTextBlock(
                    text = block.text,
                    boundingBox = scaleRect(block.boundingBox, scaleX, scaleY),
                    lineCount = block.lineCount,
                    fontSizeHintPx = block.fontSizeHintPx?.let { it * scaleY },
                )
            },
        )
    }

    private fun scaleRect(rect: Rect, scaleX: Float, scaleY: Float): Rect {
        return Rect(
            (rect.left * scaleX).roundToInt(),
            (rect.top * scaleY).roundToInt(),
            (rect.right * scaleX).roundToInt(),
            (rect.bottom * scaleY).roundToInt(),
        )
    }

    private fun buildOverlayBlocksFromRecognition(
        recognitionResult: RecognitionResult,
        fallbackRect: Rect,
        includeFallback: Boolean,
    ): List<OverlayTextBlock> {
        val blocks = recognitionResult.textBlocks
            .map { block ->
                OverlayTextBlock(
                    text = block.text.trim(),
                    boundingBox = block.boundingBox,
                    lineCountHint = block.lineCount.coerceAtLeast(1),
                    source = OverlayTextSource.Ocr,
                    fontSizeHintPx = block.fontSizeHintPx,
                )
            }
            .filter { it.text.isNotBlank() }

        if (blocks.isNotEmpty()) {
            return blocks
        }

        if (!includeFallback) {
            return emptyList()
        }

        val fallbackText = recognitionResult.result.trim()
        if (fallbackText.isBlank()) {
            return emptyList()
        }
        return listOf(
            OverlayTextBlock(
                text = fallbackText,
                boundingBox = fallbackRect,
                source = OverlayTextSource.Fallback,
            )
        )
    }

    private fun mergeTextBlocks(
        accessibilityBlocks: List<OverlayTextBlock>,
        ocrBlocks: List<OverlayTextBlock>,
        bitmap: Bitmap?,
    ): List<OverlayTextBlock> {
        val minSizePx = dpToPx(MIN_TEXT_BLOCK_DP)
        val filteredAcc = filterTextBlocks(accessibilityBlocks, minSizePx)
        val filteredOcr = filterTextBlocks(ocrBlocks, minSizePx)

        if (filteredAcc.isEmpty() && filteredOcr.isEmpty()) {
            return emptyList()
        }

        val dedupedOcr = if (filteredAcc.isEmpty()) {
            filteredOcr
        } else {
            filteredOcr.filter { ocr ->
                filteredAcc.none { acc ->
                    calculateIoU(ocr.boundingBox, acc.boundingBox) >= IOU_THRESHOLD
                }
            }
        }

        val combined = filteredAcc + dedupedOcr
        val mergedLines = mergeBlocksByLine(combined, dpToPx(MERGE_LINE_GAP_DP), bitmap)
        val mergedParagraphs = mergeBlocksByParagraph(
            mergedLines,
            maxGapPx = dpToPx(MERGE_PARAGRAPH_GAP_DP),
            alignThresholdPx = dpToPx(MERGE_PARAGRAPH_ALIGN_DP),
        )
        return mergedParagraphs.sortedWith(
            compareBy<OverlayTextBlock> { it.boundingBox.top }
                .thenBy { it.boundingBox.left }
        )
    }

    private fun filterTextBlocks(
        blocks: List<OverlayTextBlock>,
        minSizePx: Int,
    ): List<OverlayTextBlock> {
        return blocks.mapNotNull { block ->
            val text = block.text.trim()
            if (text.isBlank()) {
                return@mapNotNull null
            }
            val rect = block.boundingBox
            if (rect.width() < minSizePx || rect.height() < minSizePx) {
                return@mapNotNull null
            }
            OverlayTextBlock(
                text = text,
                boundingBox = rect,
                lineCountHint = max(1, block.lineCountHint),
                source = block.source,
                overlayStyle = block.overlayStyle,
                fontSizeHintPx = block.fontSizeHintPx,
            )
        }
    }

    private fun mergeBlocksByLine(
        blocks: List<OverlayTextBlock>,
        maxGapPx: Int,
        bitmap: Bitmap?,
    ): List<OverlayTextBlock> {
        if (blocks.isEmpty()) {
            return emptyList()
        }

        val joiner = SettingManager.textBlockJoiner.joiner
        val lines = groupIntoLines(blocks)
        val result = mutableListOf<OverlayTextBlock>()
        for (line in lines) {
            if (isMultiCardRow(line)) {
                result.addAll(line)
                continue
            }
            result.addAll(
                mergeAdjacentInLine(
                    line = line,
                    maxGapPx = maxGapPx,
                    joiner = joiner,
                    bitmap = bitmap,
                )
            )
        }
        return result
    }

    private fun mergeAdjacentInLine(
        line: List<OverlayTextBlock>,
        maxGapPx: Int,
        joiner: String,
        bitmap: Bitmap?,
    ): List<OverlayTextBlock> {
        if (line.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<OverlayTextBlock>()
        var current = line.first()
        val lineSize = line.size

        for (next in line.drop(1)) {
            if (shouldMergeHorizontally(
                    current = current,
                    next = next,
                    maxGapPx = maxGapPx,
                    lineGroupSize = lineSize,
                    bitmap = bitmap,
                )
            ) {
                val mergedText = listOf(current.text, next.text)
                    .filter { it.isNotBlank() }
                    .joinToString(joiner)
                val mergedRect = Rect(current.boundingBox)
                mergedRect.union(next.boundingBox)
                current = OverlayTextBlock(
                    text = mergedText,
                    boundingBox = mergedRect,
                    lineCountHint = max(current.lineCountHint, next.lineCountHint),
                    source = mergeSources(current.source, next.source),
                    fontSizeHintPx = mergeFontSizeHints(current.fontSizeHintPx, next.fontSizeHintPx),
                )
            } else {
                result.add(current)
                current = next
            }
        }

        result.add(current)
        return result
    }

    private fun mergeBlocksByParagraph(
        blocks: List<OverlayTextBlock>,
        maxGapPx: Int,
        alignThresholdPx: Int,
    ): List<OverlayTextBlock> {
        if (blocks.isEmpty()) {
            return emptyList()
        }

        val sorted = blocks.sortedWith(
            compareBy<OverlayTextBlock> { it.boundingBox.top }
                .thenBy { it.boundingBox.left }
        )
        val result = mutableListOf<OverlayTextBlock>()
        var current = sorted.first()

        for (next in sorted.drop(1)) {
            if (shouldMergeVertically(current.boundingBox, next.boundingBox, maxGapPx, alignThresholdPx)) {
                val mergedText = listOf(current.text, next.text)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val mergedRect = Rect(current.boundingBox)
                mergedRect.union(next.boundingBox)
                current = OverlayTextBlock(
                    text = mergedText,
                    boundingBox = mergedRect,
                    lineCountHint = max(1, current.lineCountHint) + max(1, next.lineCountHint),
                    source = mergeSources(current.source, next.source),
                    fontSizeHintPx = mergeFontSizeHints(current.fontSizeHintPx, next.fontSizeHintPx),
                )
            } else {
                result.add(current)
                current = next
            }
        }

        result.add(current)
        return result
    }

    private fun shouldMergeVertically(
        a: Rect,
        b: Rect,
        maxGapPx: Int,
        alignThresholdPx: Int,
    ): Boolean {
        if (b.top < a.top) {
            return false
        }
        val gap = b.top - a.bottom
        if (gap < 0 || gap > maxGapPx) {
            return false
        }
        val minHeight = min(a.height(), b.height()).toFloat()
        val dynamicGap = (minHeight * PARAGRAPH_GAP_HEIGHT_RATIO).roundToInt()
        if (gap > min(maxGapPx, dynamicGap)) {
            return false
        }

        val overlapWidth = min(a.right, b.right) - max(a.left, b.left)
        val minWidth = min(a.width(), b.width()).toFloat().takeIf { it > 0 } ?: return false
        val overlapRatio = overlapWidth.toFloat() / minWidth
        val leftAligned = kotlin.math.abs(a.left - b.left) <= alignThresholdPx
        return overlapRatio >= PARAGRAPH_OVERLAP_THRESHOLD || leftAligned
    }

    private fun countLineBreaks(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return 1
        }
        return trimmed.count { it == '\n' } + 1
    }

    private fun isSameLine(a: Rect, b: Rect): Boolean {
        val overlapHeight = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlapHeight <= 0) {
            return false
        }
        val minHeight = min(a.height(), b.height()).toFloat()
        return (overlapHeight / minHeight) >= LINE_OVERLAP_THRESHOLD
    }

    private fun gapBetween(a: Rect, b: Rect): Int {
        return max(0, b.left - a.right)
    }

    private fun shouldMergeHorizontally(
        current: OverlayTextBlock,
        next: OverlayTextBlock,
        maxGapPx: Int,
        lineGroupSize: Int,
        bitmap: Bitmap?,
    ): Boolean {
        if (!isSameLine(current.boundingBox, next.boundingBox)) {
            return false
        }
        if (lineGroupSize >= A11Y_NO_MERGE_MIN_BLOCKS &&
            current.source == OverlayTextSource.Accessibility &&
            next.source == OverlayTextSource.Accessibility
        ) {
            return false
        }
        val gap = gapBetween(current.boundingBox, next.boundingBox)
        val minHeight = min(current.boundingBox.height(), next.boundingBox.height()).toFloat()
        val dynamicGap = (minHeight * LINE_GAP_HEIGHT_RATIO).roundToInt()
        if (gap > min(maxGapPx, dynamicGap)) {
            return false
        }

        val fillRatio = calculateFillRatio(current.boundingBox, next.boundingBox)
        val requiredFillRatio = if (lineGroupSize >= MULTI_CARD_FORCE_MIN_BLOCKS) {
            LINE_FILL_RATIO_THRESHOLD_STRICT
        } else {
            LINE_FILL_RATIO_THRESHOLD
        }
        if (fillRatio < requiredFillRatio) {
            return false
        }
        if (bitmap != null && hasVisualSeparator(current.boundingBox, next.boundingBox, bitmap)) {
            return false
        }
        return true
    }

    private fun calculateFillRatio(a: Rect, b: Rect): Float {
        val union = Rect(a)
        union.union(b)
        val unionArea = union.width() * union.height()
        if (unionArea <= 0) {
            return 0f
        }
        val areaSum = (a.width() * a.height()) + (b.width() * b.height())
        return areaSum.toFloat() / unionArea.toFloat()
    }

    private fun hasVisualSeparator(a: Rect, b: Rect, bitmap: Bitmap): Boolean {
        val overlapTop = max(a.top, b.top).coerceAtLeast(0)
        val overlapBottom = min(a.bottom, b.bottom).coerceAtMost(bitmap.height - 1)
        val overlapHeight = overlapBottom - overlapTop
        if (overlapHeight < SEPARATOR_MIN_HEIGHT_PX) {
            return false
        }

        val gap = gapBetween(a, b)
        val minHeight = min(a.height(), b.height()).toFloat()
        if (gap > 0 && gap >= (minHeight * SEPARATOR_BLANK_GAP_HEIGHT_RATIO)) {
            val gapLeft = a.right.coerceIn(0, bitmap.width - 1)
            val gapRight = b.left.coerceIn(0, bitmap.width - 1)
            if (gapRight > gapLeft &&
                isBlankGap(
                    bitmap = bitmap,
                    gapLeft = gapLeft,
                    gapRight = gapRight,
                    overlapTop = overlapTop,
                    overlapBottom = overlapBottom,
                )
            ) {
                return true
            }
        }
        var leftX = (a.right - 1).coerceIn(0, bitmap.width - 1)
        var rightX = (b.left + 1).coerceIn(0, bitmap.width - 1)
        if (gap <= 0 || leftX >= rightX) {
            val boundary = ((a.right + b.left) / 2).coerceIn(1, bitmap.width - 2)
            leftX = boundary - 1
            rightX = boundary + 1
        }

        val step = max(1, overlapHeight / SEPARATOR_SAMPLE_COUNT)
        var y = overlapTop
        var sum = 0f
        var hits = 0
        var count = 0
        while (y <= overlapBottom) {
            val cLeft = bitmap.getPixel(leftX, y)
            val cRight = bitmap.getPixel(rightX, y)
            val diff = colorDistance(cLeft, cRight).toFloat()
            sum += diff
            if (diff >= SEPARATOR_DIFF_THRESHOLD) {
                hits++
            }
            count++
            y += step
        }
        if (count == 0) {
            return false
        }
        val avg = sum / count
        val hitRatio = hits.toFloat() / count.toFloat()
        return avg >= SEPARATOR_AVG_THRESHOLD && hitRatio >= SEPARATOR_HIT_RATIO
    }

    private fun isBlankGap(
        bitmap: Bitmap,
        gapLeft: Int,
        gapRight: Int,
        overlapTop: Int,
        overlapBottom: Int,
    ): Boolean {
        val gapWidth = gapRight - gapLeft
        if (gapWidth < SEPARATOR_BLANK_MIN_GAP_PX) {
            return false
        }
        val overlapHeight = overlapBottom - overlapTop
        if (overlapHeight < SEPARATOR_MIN_HEIGHT_PX) {
            return false
        }

        val sampleXs = listOf(
            gapLeft + gapWidth / 4,
            gapLeft + gapWidth / 2,
            gapLeft + gapWidth * 3 / 4,
        ).map { it.coerceIn(0, bitmap.width - 1) }.distinct()

        val step = max(1, overlapHeight / SEPARATOR_BLANK_SAMPLE_COUNT)
        var sum = 0f
        var sumSq = 0f
        var count = 0
        for (x in sampleXs) {
            var y = overlapTop
            while (y <= overlapBottom) {
                val color = bitmap.getPixel(x, y)
                val luma = (
                    0.2126f * android.graphics.Color.red(color) +
                        0.7152f * android.graphics.Color.green(color) +
                        0.0722f * android.graphics.Color.blue(color)
                    ) / 255f
                sum += luma
                sumSq += luma * luma
                count++
                y += step
            }
        }
        if (count == 0) {
            return false
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return variance <= SEPARATOR_BLANK_LUMA_VARIANCE_THRESHOLD
    }

    private fun colorDistance(a: Int, b: Int): Int {
        return kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
    }

    private fun groupIntoLines(
        blocks: List<OverlayTextBlock>,
    ): List<List<OverlayTextBlock>> {
        val sorted = blocks.sortedWith(
            compareBy<OverlayTextBlock> { it.boundingBox.top }
                .thenBy { it.boundingBox.left }
        )
        val lines = mutableListOf<MutableList<OverlayTextBlock>>()
        for (block in sorted) {
            val target = lines.firstOrNull { line ->
                isSameLine(line.first().boundingBox, block.boundingBox)
            }
            if (target == null) {
                lines.add(mutableListOf(block))
            } else {
                target.add(block)
            }
        }
        return lines.map { line -> line.sortedBy { it.boundingBox.left } }
    }

    private fun isMultiCardRow(line: List<OverlayTextBlock>): Boolean {
        if (line.size < MULTI_CARD_LINE_MIN_BLOCKS) {
            return false
        }

        val heights = line.map { it.boundingBox.height().toFloat() }
        val heightCv = coefficientOfVariation(heights)

        val lengths = line.map { visibleCharCount(it.text).toFloat() }
            .filter { it > 0f }
        val lengthMedian = if (lengths.isEmpty()) 0f else median(lengths)

        val gaps = line.zipWithNext { a, b ->
            max(0, b.boundingBox.left - a.boundingBox.right).toFloat()
        }
        val positiveGaps = gaps.filter { it > 0f }
        val gapMedian = if (positiveGaps.isEmpty()) 0f else median(positiveGaps)
        val gapMax = positiveGaps.maxOrNull() ?: 0f
        val gapCv = if (positiveGaps.size >= 2) coefficientOfVariation(positiveGaps) else 1f

        val widthPerChar = line.map { block ->
            val count = max(1, visibleCharCount(block.text))
            block.boundingBox.width().toFloat() / count.toFloat()
        }
        val widthPerCharMedian = median(widthPerChar)
        val paddedRatio = widthPerChar.count { it > widthPerCharMedian * MULTI_CARD_PADDED_MULTIPLIER }
            .toFloat() / line.size.toFloat()

        val shortText = lengthMedian in 1f..MULTI_CARD_TEXT_LENGTH_MAX.toFloat()
        val heightConsistent = heightCv < MULTI_CARD_HEIGHT_CV
        val spacingRegular = gapCv < MULTI_CARD_GAP_CV
        val gapOutlier = line.size >= MULTI_CARD_FORCE_MIN_BLOCKS &&
            gapMedian > 0f &&
            gapMax >= gapMedian * MULTI_CARD_GAP_OUTLIER_RATIO
        val gapHighlyVaried = line.size >= MULTI_CARD_FORCE_MIN_BLOCKS &&
            positiveGaps.size >= 2 &&
            gapCv >= MULTI_CARD_GAP_CV_HIGH
        val forcedMultiCard = line.size >= MULTI_CARD_FORCE_MIN_BLOCKS &&
            lengthMedian <= MULTI_CARD_TEXT_LENGTH_FORCE_MAX &&
            heightCv < MULTI_CARD_FORCE_HEIGHT_CV
        return gapOutlier || gapHighlyVaried || forcedMultiCard ||
            (shortText && heightConsistent && (spacingRegular || paddedRatio >= MULTI_CARD_PADDED_RATIO))
    }

    private fun visibleCharCount(text: String): Int {
        return text.count { !it.isWhitespace() }
    }

    private fun coefficientOfVariation(values: List<Float>): Float {
        if (values.isEmpty()) {
            return 0f
        }
        val mean = values.sum() / values.size
        if (mean == 0f) {
            return 0f
        }
        var sumSq = 0f
        values.forEach { value ->
            val diff = value - mean
            sumSq += diff * diff
        }
        val variance = sumSq / values.size
        return sqrt(variance) / mean
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

    private fun mergeSources(a: OverlayTextSource, b: OverlayTextSource): OverlayTextSource {
        if (a == b) {
            return a
        }
        if (a == OverlayTextSource.Unknown) {
            return b
        }
        if (b == OverlayTextSource.Unknown) {
            return a
        }
        return OverlayTextSource.Mixed
    }

    private fun mergeFontSizeHints(a: Float?, b: Float?): Float? {
        if (a == null) {
            return b
        }
        if (b == null) {
            return a
        }
        return min(a, b)
    }

    private fun calculateIoU(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) {
            return 0f
        }

        val intersectionArea = (right - left) * (bottom - top)
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - intersectionArea
        if (unionArea <= 0) {
            return 0f
        }
        return intersectionArea.toFloat() / unionArea.toFloat()
    }

    private sealed interface BlockTranslationResult {
        data class Success(val translatedBlocks: List<OverlayTextBlock>) : BlockTranslationResult
        data class Failed(val error: Throwable) : BlockTranslationResult
        data class SourceLangNotSupport(val type: TranslationProviderType) : BlockTranslationResult
    }

    private suspend fun translateBlocks(
        translator: Translator,
        sourceLangCode: String,
        originalBlocks: List<OverlayTextBlock>,
        fallbackRect: Rect,
    ): BlockTranslationResult {
        if (originalBlocks.isEmpty()) {
            return BlockTranslationResult.Success(emptyList())
        }

        return if (translator.type == TranslationProviderType.Deepl) {
            translateBlocksWithDeepl(
                sourceLangCode = sourceLangCode,
                originalBlocks = originalBlocks,
            )
        } else {
            translateBlocksWithDelimiter(
                translator = translator,
                sourceLangCode = sourceLangCode,
                originalBlocks = originalBlocks,
                fallbackRect = fallbackRect,
            )
        }
    }

    private suspend fun translateBlocksWithDelimiter(
        translator: Translator,
        sourceLangCode: String,
        originalBlocks: List<OverlayTextBlock>,
        fallbackRect: Rect,
    ): BlockTranslationResult {
        val delimiterToken = "<<<OCR_BLOCK_${UUID.randomUUID()}>>>"
        val mergedText = originalBlocks.joinToString(separator = "\n$delimiterToken\n") { it.text }

        return when (val translationResult = translator.translate(
            text = mergedText,
            sourceLangCode = sourceLangCode,
        )) {
            is TranslationResult.TranslatedResult -> {
                val pieces = splitTranslatedText(translationResult.result, delimiterToken)
                BlockTranslationResult.Success(
                    buildTranslatedBlocks(
                        pieces = pieces,
                        originalBlocks = originalBlocks,
                        fallbackRect = fallbackRect,
                        fallbackText = translationResult.result,
                    )
                )
            }

            is TranslationResult.SourceLangNotSupport ->
                BlockTranslationResult.SourceLangNotSupport(translationResult.type)

            is TranslationResult.TranslationFailed ->
                BlockTranslationResult.Failed(translationResult.error)

            TranslationResult.OCROnlyResult,
            TranslationResult.OuterTranslatorLaunched ->
                BlockTranslationResult.Failed(
                    IllegalStateException(
                        context.getString(R.string.error_full_screen_translation_not_supported)
                    )
                )
        }
    }

    private suspend fun translateBlocksWithDeepl(
        sourceLangCode: String,
        originalBlocks: List<OverlayTextBlock>,
    ): BlockTranslationResult {
        if (!DeeplTranslator.isLangSupport()) {
            return BlockTranslationResult.SourceLangNotSupport(TranslationProviderType.Deepl)
        }

        val targetLangCode = DeeplTranslator.supportedLanguages()
            .firstOrNull { it.selected }
            ?.code
            ?: return BlockTranslationResult.Failed(
                IllegalArgumentException("The selected translation language is not found")
            )

        if (sourceLangCode.firstPart().equals(targetLangCode.firstPart(), ignoreCase = true)) {
            val translatedBlocks = originalBlocks.map { block ->
                OverlayTextBlock(
                    text = block.text,
                    boundingBox = block.boundingBox,
                    lineCountHint = block.lineCountHint,
                    source = block.source,
                    fontSizeHintPx = block.fontSizeHintPx,
                )
            }
            return BlockTranslationResult.Success(translatedBlocks)
        }

        val sourceLang = DeeplTranslator.toDeeplLang(sourceLangCode).takeUnless { it.isNullOrBlank() }
        val targetLang = DeeplTranslator.toDeeplLang(targetLangCode) ?: targetLangCode

        val originalTexts = originalBlocks.map { it.text }
        val resolved = MutableList<String?>(originalTexts.size) { null }
        val pending = mutableListOf<IndexedValue<String>>()

        originalTexts.forEachIndexed { index, text ->
            val key = buildCacheKey(
                text = text,
                sourceLangCode = sourceLangCode,
                targetLangCode = targetLangCode,
                providerType = TranslationProviderType.Deepl,
            )
            val cached = getCachedTranslation(key)
            if (cached != null) {
                resolved[index] = cached
            } else {
                pending.add(IndexedValue(index, text))
            }
        }

        if (pending.isNotEmpty()) {
            val chunks = chunkIndexedTexts(
                items = pending,
                maxBlocks = MAX_TRANSLATION_BLOCKS,
                maxChars = MAX_TRANSLATION_CHARS,
            )
            for (chunk in chunks) {
                val chunkTexts = chunk.map { it.value }
                val chunkResult = DeeplTranslatorAPI.translate(
                    text = chunkTexts,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                )
                val translations = chunkResult.getOrElse {
                    return BlockTranslationResult.Failed(it)
                }
                if (translations.size != chunk.size) {
                    return BlockTranslationResult.Failed(
                        IllegalStateException("Translation result size mismatch")
                    )
                }
                translations.forEachIndexed { index, translated ->
                    val originalIndex = chunk[index].index
                    resolved[originalIndex] = translated
                    val key = buildCacheKey(
                        text = chunk[index].value,
                        sourceLangCode = sourceLangCode,
                        targetLangCode = targetLangCode,
                        providerType = TranslationProviderType.Deepl,
                    )
                    putCachedTranslation(key, translated)
                }
            }
        }

        val finalTexts = resolved.mapIndexed { index, text ->
            text ?: originalTexts[index]
        }
        val translatedBlocks = originalBlocks.mapIndexed { index, block ->
            OverlayTextBlock(
                text = finalTexts[index],
                boundingBox = block.boundingBox,
                lineCountHint = block.lineCountHint,
                source = block.source,
                fontSizeHintPx = block.fontSizeHintPx,
            )
        }
        return BlockTranslationResult.Success(translatedBlocks)
    }

    private fun chunkIndexedTexts(
        items: List<IndexedValue<String>>,
        maxBlocks: Int,
        maxChars: Int,
    ): List<List<IndexedValue<String>>> {
        if (items.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<MutableList<IndexedValue<String>>>()
        var current = mutableListOf<IndexedValue<String>>()
        var currentChars = 0

        for (item in items) {
            val itemLength = item.value.length
            val exceedBlocks = current.size >= maxBlocks
            val exceedChars = current.isNotEmpty() && (currentChars + itemLength) > maxChars
            if (exceedBlocks || exceedChars) {
                chunks.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(item)
            currentChars += itemLength
        }

        if (current.isNotEmpty()) {
            chunks.add(current)
        }

        return chunks
    }

    private fun buildCacheKey(
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
        providerType: TranslationProviderType,
    ): String {
        return "${providerType.key}|${sourceLangCode}|${targetLangCode}|$text"
    }

    private fun getCachedTranslation(key: String): String? {
        return synchronized(translationCache) {
            translationCache[key]
        }
    }

    private fun putCachedTranslation(key: String, value: String) {
        synchronized(translationCache) {
            translationCache[key] = value
        }
    }

    private fun splitTranslatedText(
        translatedText: String,
        delimiterToken: String,
    ): List<String> {
        val delimiterRegex = Regex("\\s*${Regex.escape(delimiterToken)}\\s*")
        return translatedText.split(delimiterRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).roundToInt()
    }

    private fun buildTranslatedBlocks(
        pieces: List<String>,
        originalBlocks: List<OverlayTextBlock>,
        fallbackRect: Rect,
        fallbackText: String,
    ): List<OverlayTextBlock> {
        return if (pieces.size == originalBlocks.size) {
            originalBlocks.mapIndexed { index, block ->
                OverlayTextBlock(
                    text = pieces[index],
                    boundingBox = block.boundingBox,
                    lineCountHint = block.lineCountHint,
                    source = block.source,
                    fontSizeHintPx = block.fontSizeHintPx,
                )
            }
        } else {
            listOf(
                OverlayTextBlock(
                    text = fallbackText.trim(),
                    boundingBox = fallbackRect,
                    lineCountHint = countLineBreaks(fallbackText),
                    source = OverlayTextSource.Fallback,
                )
            )
        }
    }

    private companion object {
        const val MAX_OCR_WIDTH_PX = 1080
        const val MIN_TEXT_BLOCK_DP = 8
        const val MERGE_LINE_GAP_DP = 12
        const val MERGE_PARAGRAPH_GAP_DP = 8
        const val MERGE_PARAGRAPH_ALIGN_DP = 12
        const val MAX_TRANSLATION_BLOCKS = 60
        const val MAX_TRANSLATION_CHARS = 4000
        const val MAX_TRANSLATION_CACHE_SIZE = 500
        const val IOU_THRESHOLD = 0.7f
        const val LINE_OVERLAP_THRESHOLD = 0.6f
        const val PARAGRAPH_OVERLAP_THRESHOLD = 0.6f
        const val LINE_GAP_HEIGHT_RATIO = 0.45f
        const val PARAGRAPH_GAP_HEIGHT_RATIO = 0.5f
        const val LINE_FILL_RATIO_THRESHOLD = 0.78f
        const val LINE_FILL_RATIO_THRESHOLD_STRICT = 0.86f
        const val MULTI_CARD_LINE_MIN_BLOCKS = 4
        const val MULTI_CARD_TEXT_LENGTH_MAX = 6
        const val MULTI_CARD_HEIGHT_CV = 0.2f
        const val MULTI_CARD_GAP_CV = 0.35f
        const val MULTI_CARD_GAP_CV_HIGH = 0.6f
        const val MULTI_CARD_GAP_OUTLIER_RATIO = 2.2f
        const val MULTI_CARD_PADDED_RATIO = 0.6f
        const val MULTI_CARD_PADDED_MULTIPLIER = 1.6f
        const val MULTI_CARD_FORCE_MIN_BLOCKS = 3
        const val MULTI_CARD_TEXT_LENGTH_FORCE_MAX = 8f
        const val MULTI_CARD_FORCE_HEIGHT_CV = 0.25f
        const val A11Y_NO_MERGE_MIN_BLOCKS = 3
        const val SEPARATOR_MIN_HEIGHT_PX = 6
        const val SEPARATOR_SAMPLE_COUNT = 10
        const val SEPARATOR_DIFF_THRESHOLD = 60
        const val SEPARATOR_AVG_THRESHOLD = 50f
        const val SEPARATOR_HIT_RATIO = 0.5f
        const val SEPARATOR_BLANK_GAP_HEIGHT_RATIO = 0.8f
        const val SEPARATOR_BLANK_MIN_GAP_PX = 4
        const val SEPARATOR_BLANK_SAMPLE_COUNT = 8
        const val SEPARATOR_BLANK_LUMA_VARIANCE_THRESHOLD = 0.004f
    }

    private fun startTranslation(
        croppedBitmap: Bitmap,
        parentRect: Rect,
        selectedRect: Rect,
        recognitionResult: RecognitionResult,
    ) = scope.launch {
        try {
            val translator = Translator.getTranslator()

            stateNavigator.updateState(
                NavState.TextTranslating(
                    parentRect = parentRect,
                    selectedRect = selectedRect,
                    croppedBitmap = croppedBitmap,
                    recognitionResult = recognitionResult,
                    translationProviderType = translator.type,
                )
            )

            FirebaseEvent.logStartTranslationText(
                text = recognitionResult.result,
                fromLang = recognitionResult.langCode,
                translator = translator,
            )

            val translationResult = translator.translate(
                text = recognitionResult.result,
                sourceLangCode = recognitionResult.langCode,
            )

            stateNavigator.navigate(
                NavigationAction.NavigateToTranslated(
                    croppedBitmap = croppedBitmap,
                    parentRect = parentRect,
                    selectedRect = selectedRect,
                    recognitionResult = recognitionResult,
                    translator = translator,
                    translationResult = translationResult,
                )
            )
        } catch (e: Exception) {
            logger.warn(t = e)
            FirebaseEvent.logException(e)
            showError(e.message ?: "Unknown error found while translating")
        }
    }

    private fun onTranslated(
        croppedBitmap: Bitmap,
        parentRect: Rect,
        selectedRect: Rect,
        recognitionResult: RecognitionResult,
        translator: Translator,
        translationResult: TranslationResult,
    ) {
        when (translationResult) {
            TranslationResult.OuterTranslatorLaunched -> {
                FirebaseEvent.logTranslationTextFinished(translator)
                backToIdle()
            }

            is TranslationResult.SourceLangNotSupport -> {
                FirebaseEvent.logTranslationSourceLangNotSupport(
                    translator, recognitionResult.langCode,
                )

                showError(context.getString(R.string.msg_translator_provider_does_not_support_the_ocr_lang))
            }

            TranslationResult.OCROnlyResult -> {
                FirebaseEvent.logTranslationTextFinished(translator)

                stateNavigator.updateState(
                    NavState.TextTranslated(
                        parentRect = parentRect,
                        selectedRect = selectedRect,
                        croppedBitmap = croppedBitmap,
                        recognitionResult = recognitionResult,
                        resultInfo = ResultInfo.OCROnly,
                    )
                )
            }

            is TranslationResult.TranslatedResult -> {
                FirebaseEvent.logTranslationTextFinished(translator)

                stateNavigator.updateState(
                    NavState.TextTranslated(
                        parentRect = parentRect,
                        selectedRect = selectedRect,
                        croppedBitmap = croppedBitmap,
                        recognitionResult = recognitionResult,
                        resultInfo = ResultInfo.Translated(
                            translatedText = translationResult.result,
                            providerType = translationResult.type,
                        ),
                    )
                )
            }

            is TranslationResult.TranslationFailed -> {
                FirebaseEvent.logTranslationTextFailed(translator)
                val error = translationResult.error

                if (error is MicrosoftAzureTranslator.Error) {
                    FirebaseEvent.logMicrosoftTranslationError(error)
                }

                if (error is IOException) {
                    showError(context.getString(R.string.error_can_not_connect_to_translation_server))
                } else {
                    FirebaseEvent.logException(error)
                    showError(
                        error.localizedMessage
                            ?: context.getString(R.string.error_unknown)
                    )
                }
            }
        }
    }

    private fun showError(error: String) = scope.launch {
        logger.error("showError(): $error")
        backToIdle()
        action.emit(StateOperatorAction.ShowErrorDialog(error))
    }

    private fun backToIdle() = scope.launch {
        if (currentNavState != NavState.Idle)
            stateNavigator.updateState(NavState.Idle)

        action.emit(StateOperatorAction.HideResultView)
        action.emit(StateOperatorAction.HideFullScreenTranslationView)

        currentNavState.getBitmap()?.setReusable()
    }

    private fun createFullScreenRect(): Rect {
        val screenSize = UIUtils.realSize
        return Rect(0, 0, screenSize.x, screenSize.y)
    }

    private fun NavState.getBitmap(): Bitmap? =
        (this as? BitmapIncluded)?.bitmap
}

sealed interface StateOperatorAction {
    data object TopMainBar : StateOperatorAction
    data object ShowScreenCirclingView : StateOperatorAction
    data object HideScreenCirclingView : StateOperatorAction
    data object ShowResultView : StateOperatorAction
    data object HideResultView : StateOperatorAction
    data object ShowFullScreenTranslationView : StateOperatorAction
    data object HideFullScreenTranslationView : StateOperatorAction
    data class ShowErrorDialog(val error: String) : StateOperatorAction
}

sealed class Result(
    open val ocrText: String,
    open val boundingBoxes: List<Rect>,
) {
    data class Translated(
        override val ocrText: String,
        override val boundingBoxes: List<Rect>,
        val translatedText: String,
        val providerType: TranslationProviderType,
    ) : Result(ocrText, boundingBoxes)

    data class SourceLangNotSupport(
        override val ocrText: String,
        override val boundingBoxes: List<Rect>,
        val providerType: TranslationProviderType,
    ) : Result(ocrText, boundingBoxes)

    data class OCROnly(
        override val ocrText: String,
        override val boundingBoxes: List<Rect>,
    ) : Result(ocrText, boundingBoxes)
}

sealed interface ResultInfo {
    data class Translated(
        val translatedText: String,
        val providerType: TranslationProviderType,
    ) : ResultInfo

    data class Error(
        val providerType: TranslationProviderType,
        val resultError: ResultError,
    ) : ResultInfo

    data object OCROnly : ResultInfo
}

enum class ResultError {
    SourceLangNotSupport,
}
