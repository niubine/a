package tw.firemaples.onscreenocr.repo

import android.graphics.Rect
import junit.framework.TestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class GeneralRepositoryTest : TestCase() {

    private val repo = GeneralRepository()

    public override fun setUp() {
        super.setUp()
    }

    public override fun tearDown() {}

    fun testLastRememberedSelectionArea() = runBlocking {
        val rect = Rect(1, 2, 3, 4)
        repo.setLastRememberedSelectionArea(rect)
        val result = repo.getLastRememberedSelectionArea().first()

        assertEquals(rect, result)
    }

}
