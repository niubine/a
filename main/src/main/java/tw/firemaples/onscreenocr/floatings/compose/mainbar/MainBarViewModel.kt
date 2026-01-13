package tw.firemaples.onscreenocr.floatings.compose.mainbar

import android.graphics.Point
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.firemaples.onscreenocr.R
import tw.firemaples.onscreenocr.data.usecase.GetCurrentOCRDisplayLangCodeUseCase
import tw.firemaples.onscreenocr.data.usecase.GetCurrentOCRLangUseCase
import tw.firemaples.onscreenocr.data.usecase.GetCurrentTranslationLangUseCase
import tw.firemaples.onscreenocr.data.usecase.GetCurrentTranslatorTypeUseCase
import tw.firemaples.onscreenocr.data.usecase.GetMainBarInitialPositionUseCase
import tw.firemaples.onscreenocr.data.usecase.SaveLastMainBarPositionUseCase
import tw.firemaples.onscreenocr.di.MainImmediateCoroutineScope
import tw.firemaples.onscreenocr.floatings.compose.base.awaitForSubscriber
import tw.firemaples.onscreenocr.floatings.manager.NavState
import tw.firemaples.onscreenocr.floatings.manager.NavigationAction
import tw.firemaples.onscreenocr.floatings.manager.StateNavigator
import tw.firemaples.onscreenocr.pages.setting.SettingManager
import tw.firemaples.onscreenocr.translator.TranslationProviderType
import javax.inject.Inject

interface MainBarViewModel {
    val state: StateFlow<MainBarState>
    val action: SharedFlow<MainBarAction>
    fun getInitialPosition(): Point
    fun getFadeOutAfterMoved(): Boolean
    fun getFadeOutDelay(): Long
    fun getFadeOutDestinationAlpha(): Float
    fun onFloatingBallClicked()
    fun onRegionCaptureClicked()
    fun onFullScreenCaptureClicked()
    fun onFullScreenTranslateClicked()
    fun onSettingsClicked()
    fun onCancelClicked()
    fun onAttachedToScreen()
    fun onDragEnd(x: Int, y: Int)
    fun onLanguageBlockClicked()
}

data class MainBarState(
    val drawMainBar: Boolean = true,
    val toolbarVisible: Boolean = false,
    val isIdle: Boolean = true,
    val canStartRegionCapture: Boolean = false,
    val showCancelButton: Boolean = false,
    val langText: String = "",
    val translatorIcon: Int? = null,
)

sealed interface MainBarAction {
    data object RescheduleFadeOut : MainBarAction
    data object MoveToEdgeIfEnabled : MainBarAction
    data object OpenLanguageSelectionPanel : MainBarAction
    data object OpenSettings : MainBarAction
    data object HideMainBar : MainBarAction
    data object ExitApp : MainBarAction
}

@Suppress("LongParameterList", "TooManyFunctions")
class MainBarViewModelImpl @Inject constructor(
    @MainImmediateCoroutineScope
    private val scope: CoroutineScope,
    private val stateNavigator: StateNavigator,
    private val getCurrentOCRLangUseCase: GetCurrentOCRLangUseCase,
    private val getCurrentOCRDisplayLangCodeUseCase: GetCurrentOCRDisplayLangCodeUseCase,
    private val getCurrentTranslatorTypeUseCase: GetCurrentTranslatorTypeUseCase,
    private val getCurrentTranslationLangUseCase: GetCurrentTranslationLangUseCase,
    private val saveLastMainBarPositionUseCase: SaveLastMainBarPositionUseCase,
    private val getMainBarInitialPositionUseCase: GetMainBarInitialPositionUseCase,
) : MainBarViewModel {
    override val state = MutableStateFlow(MainBarState())
    override val action = MutableSharedFlow<MainBarAction>()

    init {
        stateNavigator.currentNavState
            .onEach { onNavigationStateChanges(it) }
            .launchIn(scope)
        subscribeLanguageStateChanges()
    }

    private suspend fun onNavigationStateChanges(navState: NavState) {
        state.update {
            val drawMainBar = when (navState) {
                NavState.Idle,
                NavState.ScreenCircling,
                is NavState.ScreenCircled -> true

                else -> false
            }

            val canStartRegionCapture = navState is NavState.ScreenCircled
            val showCancelButton =
                navState == NavState.ScreenCircling || navState is NavState.ScreenCircled

            it.copy(
                drawMainBar = drawMainBar,
                toolbarVisible = if (drawMainBar) it.toolbarVisible else false,
                isIdle = navState == NavState.Idle,
                canStartRegionCapture = canStartRegionCapture,
                showCancelButton = showCancelButton,
            )
        }
        action.emit(MainBarAction.MoveToEdgeIfEnabled)
    }

    private fun subscribeLanguageStateChanges() {
        combine(
            getCurrentOCRDisplayLangCodeUseCase.invoke(),
            getCurrentTranslatorTypeUseCase.invoke(),
            getCurrentTranslationLangUseCase.invoke(),
        ) { ocrLang, translatorType, translationLang ->
            updateLanguageStates(
                ocrLang = ocrLang,
                translationProviderType = translatorType,
                translationLang = translationLang,
            )
        }.launchIn(scope)
    }

    private suspend fun updateLanguageStates(
        ocrLang: String,
        translationProviderType: TranslationProviderType,
        translationLang: String,
    ) {
        val icon = when (translationProviderType) {
            TranslationProviderType.GoogleTranslateApp -> R.drawable.ic_google_translate_dark_grey
            TranslationProviderType.BingTranslateApp -> R.drawable.ic_microsoft_bing
            TranslationProviderType.OtherTranslateApp -> R.drawable.ic_open_in_app
            TranslationProviderType.MicrosoftAzure,
            TranslationProviderType.GoogleMLKit,
            TranslationProviderType.Deepl,
            TranslationProviderType.MyMemory,
            TranslationProviderType.PapagoTranslateApp,
            TranslationProviderType.YandexTranslateApp,
            TranslationProviderType.OCROnly -> null
        }

        val text = when (translationProviderType) {
            TranslationProviderType.GoogleTranslateApp,
            TranslationProviderType.BingTranslateApp,
            TranslationProviderType.OtherTranslateApp -> "$ocrLang>"

            TranslationProviderType.YandexTranslateApp -> "$ocrLang > Y"
            TranslationProviderType.PapagoTranslateApp -> "$ocrLang > P"
            TranslationProviderType.OCROnly -> " $ocrLang "
            TranslationProviderType.MicrosoftAzure,
            TranslationProviderType.GoogleMLKit,
            TranslationProviderType.Deepl,
            TranslationProviderType.MyMemory -> "$ocrLang>$translationLang"
        }

        state.update {
            it.copy(
                langText = text,
                translatorIcon = icon,
            )
        }
        action.emit(MainBarAction.MoveToEdgeIfEnabled)
    }

    override fun getInitialPosition(): Point =
        getMainBarInitialPositionUseCase.invoke()

    override fun getFadeOutAfterMoved(): Boolean {
        val navState = stateNavigator.currentNavState.value

        return navState != NavState.ScreenCircling && navState !is NavState.ScreenCircled
                && !state.value.toolbarVisible
                && SettingManager.enableFadingOutWhileIdle //TODO move logic
    }

    override fun getFadeOutDelay(): Long =
        SettingManager.timeoutToFadeOut //TODO move logic

    override fun getFadeOutDestinationAlpha(): Float =
        SettingManager.opaquePercentageToFadeOut //TODO move logic

    override fun onFloatingBallClicked() {
        scope.launch {
            action.emit(MainBarAction.RescheduleFadeOut)
            state.update {
                it.copy(
                    toolbarVisible = !it.toolbarVisible,
                )
            }
        }
    }

    override fun onRegionCaptureClicked() {
        scope.launch {
            action.emit(MainBarAction.RescheduleFadeOut)
            when {
                state.value.canStartRegionCapture -> {
                    val (ocrProvider, ocrLang) = getCurrentOCRLangUseCase.invoke().first()
                    stateNavigator.navigate(
                        NavigationAction.NavigateToScreenCapturing(
                            ocrLang = ocrLang,
                            ocrProvider = ocrProvider,
                        )
                    )
                    state.update {
                        it.copy(toolbarVisible = false)
                    }
                }

                state.value.isIdle -> {
                    stateNavigator.navigate(NavigationAction.NavigateToScreenCircling)
                    state.update {
                        it.copy(toolbarVisible = false)
                    }
                }
            }
        }
    }

    override fun onFullScreenCaptureClicked() {
        scope.launch {
            action.emit(MainBarAction.RescheduleFadeOut)
            if (!state.value.isIdle) return@launch

            val (ocrProvider, ocrLang) = getCurrentOCRLangUseCase.invoke().first()
            stateNavigator.navigate(
                NavigationAction.NavigateToFullScreenCapturing(
                    ocrLang = ocrLang,
                    ocrProvider = ocrProvider,
                )
            )
            state.update {
                it.copy(toolbarVisible = false)
            }
        }
    }

    override fun onFullScreenTranslateClicked() {
        scope.launch {
            action.emit(MainBarAction.RescheduleFadeOut)
            if (!state.value.isIdle) return@launch

            val (ocrProvider, ocrLang) = getCurrentOCRLangUseCase.invoke().first()
            stateNavigator.navigate(
                NavigationAction.NavigateToFullScreenTranslation(
                    ocrLang = ocrLang,
                    ocrProvider = ocrProvider,
                )
            )
            state.update {
                it.copy(
                    toolbarVisible = false,
                )
            }
        }
    }

    override fun onSettingsClicked() {
        scope.launch {
            action.emit(MainBarAction.RescheduleFadeOut)
            action.emit(MainBarAction.OpenSettings)
            state.update {
                it.copy(toolbarVisible = false)
            }
        }
    }

    override fun onCancelClicked() {
        scope.launch {
            action.emit(MainBarAction.RescheduleFadeOut)
            stateNavigator.navigate(NavigationAction.CancelScreenCircling)
            state.update {
                it.copy(toolbarVisible = false)
            }
        }
    }

    override fun onAttachedToScreen() {
        scope.launch {
            action.awaitForSubscriber()
            action.emit(MainBarAction.RescheduleFadeOut)
        }
    }

    override fun onDragEnd(x: Int, y: Int) {
        scope.launch {
            saveLastMainBarPositionUseCase.invoke(x = x, y = y)
        }
    }

    override fun onLanguageBlockClicked() {
        scope.launch {
            action.emit(MainBarAction.RescheduleFadeOut)
            action.emit(MainBarAction.OpenLanguageSelectionPanel)
            state.update {
                it.copy(toolbarVisible = false)
            }
        }
    }
}
