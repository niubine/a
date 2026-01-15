package tw.firemaples.onscreenocr.repo

import android.graphics.Point
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import tw.firemaples.onscreenocr.pages.setting.SettingManager
import tw.firemaples.onscreenocr.pref.AppPref

class GeneralRepository {
    fun isRememberLastSelection(): Flow<Boolean> = flow {
        emit(SettingManager.saveLastSelectionArea)
    }.flowOn(Dispatchers.Default)

    fun getLastRememberedSelectionArea(): Flow<Rect?> = flow {
        emit(AppPref.lastSelectionArea)
    }.flowOn(Dispatchers.Default)

    suspend fun setLastRememberedSelectionArea(rect: Rect) {
        withContext(Dispatchers.Default) {
            AppPref.lastSelectionArea = rect
        }
    }

    fun saveLastMainBarPosition(x: Int, y: Int) {
        AppPref.lastMainBarPosition = Point(x, y)
    }

    fun isAutoCopyOCRResult(): Flow<Boolean> = flow {
        emit(SettingManager.autoCopyOCRResult)
    }.flowOn(Dispatchers.Default)

    fun hideRecognizedTextAfterTranslated(): Flow<Boolean> = flow {
        emit(SettingManager.hideRecognizedResultAfterTranslated)
    }.flowOn(Dispatchers.Default)

    data class Record(val version: String, val desc: String)
}
