package tw.firemaples.onscreenocr.floatings.compose.fullscreen

import android.content.Context
import android.view.WindowManager
import androidx.compose.runtime.Composable
import dagger.hilt.android.qualifiers.ApplicationContext
import tw.firemaples.onscreenocr.floatings.compose.base.ComposeFloatingView
import tw.firemaples.onscreenocr.utils.getViewRect
import javax.inject.Inject

class FullScreenTranslationFloatingView @Inject constructor(
    @ApplicationContext context: Context,
    private val viewModel: FullScreenTranslationViewModel,
) : ComposeFloatingView(context) {

    override val fullscreenMode: Boolean
        get() = true

    override val layoutWidth: Int
        get() = WindowManager.LayoutParams.MATCH_PARENT

    override val layoutHeight: Int
        get() = WindowManager.LayoutParams.MATCH_PARENT

    @Composable
    override fun RootContent() {
        FullScreenTranslationContent(
            viewModel = viewModel,
            requestRootLocationOnScreen = rootView::getViewRect,
        )
    }
}
