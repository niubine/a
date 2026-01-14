package tw.firemaples.onscreenocr.floatings.compose.fullscreen

import android.graphics.Rect
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.firemaples.onscreenocr.di.MainImmediateCoroutineScope
import tw.firemaples.onscreenocr.floatings.manager.NavState
import tw.firemaples.onscreenocr.floatings.manager.NavigationAction
import tw.firemaples.onscreenocr.floatings.manager.OverlayTextBlock
import tw.firemaples.onscreenocr.floatings.manager.StateNavigator
import tw.firemaples.onscreenocr.recognition.RecognitionResult
import javax.inject.Inject

interface FullScreenTranslationViewModel {
    val state: StateFlow<FullScreenTranslationState>
    fun onRootViewPositioned(xOffset: Int, yOffset: Int)
    fun onPressStart()
    fun onPressEnd()
    fun onSwipeToDismiss()
}

data class FullScreenTranslationState(
    val isProcessing: Boolean = false,
    val showOriginal: Boolean = false,
    val originalBlocks: List<OverlayTextBlock> = emptyList(),
    val translatedBlocks: List<OverlayTextBlock> = emptyList(),
    val rootOffset: IntOffset = IntOffset.Zero,
)

class FullScreenTranslationViewModelImpl @Inject constructor(
    @MainImmediateCoroutineScope
    private val scope: CoroutineScope,
    private val stateNavigator: StateNavigator,
) : FullScreenTranslationViewModel {
    override val state = MutableStateFlow(FullScreenTranslationState())

    init {
        stateNavigator.currentNavState
            .onEach { navState ->
                updateState(navState)
            }
            .launchIn(scope)
    }

    override fun onRootViewPositioned(xOffset: Int, yOffset: Int) {
        state.update {
            it.copy(rootOffset = IntOffset(xOffset, yOffset))
        }
    }

    override fun onPressStart() {
        state.update {
            it.copy(showOriginal = true)
        }
    }

    override fun onPressEnd() {
        state.update {
            it.copy(showOriginal = false)
        }
    }

    override fun onSwipeToDismiss() {
        scope.launch {
            stateNavigator.navigate(NavigationAction.NavigateToIdle)
        }
    }

    private fun updateState(navState: NavState) {
        when (navState) {
            NavState.FullScreenCapturing -> {
                state.update {
                    it.copy(
                        isProcessing = true,
                        showOriginal = false,
                        originalBlocks = emptyList(),
                        translatedBlocks = emptyList(),
                    )
                }
            }

            is NavState.FullScreenTextRecognizing -> {
                state.update {
                    it.copy(
                        isProcessing = true,
                        showOriginal = false,
                        originalBlocks = emptyList(),
                        translatedBlocks = emptyList(),
                    )
                }
            }

            is NavState.FullScreenTextTranslating -> {
                val originalBlocks = navState.originalBlocks.ifEmpty {
                    buildOriginalBlocks(
                        recognitionResult = navState.recognitionResult,
                        fallbackRect = navState.selectedRect,
                    )
                }
                state.update {
                    it.copy(
                        isProcessing = true,
                        showOriginal = false,
                        originalBlocks = originalBlocks,
                        translatedBlocks = emptyList(),
                    )
                }
            }

            is NavState.FullScreenTextTranslated -> {
                state.update {
                    it.copy(
                        isProcessing = false,
                        showOriginal = false,
                        originalBlocks = navState.result.originalBlocks,
                        translatedBlocks = navState.result.translatedBlocks,
                    )
                }
            }

            NavState.Idle -> {
                state.update {
                    FullScreenTranslationState()
                }
            }

            else -> Unit
        }
    }

    private fun buildOriginalBlocks(
        recognitionResult: RecognitionResult,
        fallbackRect: Rect,
    ): List<OverlayTextBlock> {
        val blocks = recognitionResult.textBlocks
        if (blocks.isEmpty()) {
            if (recognitionResult.result.isBlank()) return emptyList()
            return listOf(
                OverlayTextBlock(
                    text = recognitionResult.result,
                    boundingBox = fallbackRect,
                    lineCountHint = countLineBreaks(recognitionResult.result),
                )
            )
        }
        return blocks.filter { it.text.isNotBlank() }.map { block ->
            OverlayTextBlock(
                text = block.text,
                boundingBox = block.boundingBox,
                lineCountHint = block.lineCount.coerceAtLeast(1),
            )
        }
    }

    private fun countLineBreaks(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return 1
        }
        return trimmed.count { it == '\n' } + 1
    }
}
