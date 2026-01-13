package tw.firemaples.onscreenocr.floatings.compose.fullscreen

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface FullScreenTranslationModule {
    @Binds
    fun bindFullScreenTranslationViewModel(
        viewModel: FullScreenTranslationViewModelImpl,
    ): FullScreenTranslationViewModel
}
