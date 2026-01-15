package tw.firemaples.onscreenocr.floatings.manager

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import tw.firemaples.onscreenocr.utils.Logger
import kotlin.math.max
import kotlin.math.roundToInt

object FullScreenBitmapCleaner {
    private val logger: Logger by lazy { Logger(this::class) }
    @Volatile
    private var openCvReady = false

    fun cleanBitmap(
        bitmap: Bitmap,
        blocks: List<OverlayTextBlock>,
    ): Bitmap? {
        if (blocks.isEmpty() || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }
        if (!ensureOpenCv()) {
            return null
        }

        val source = Mat()
        val output = Mat()
        val mask = Mat.zeros(bitmap.height, bitmap.width, CvType.CV_8UC1)
        var hasInpaint = false

        return try {
            Utils.bitmapToMat(bitmap, source)
            source.copyTo(output)

            blocks.forEach { block ->
                val rect = clampRect(block.boundingBox, bitmap.width, bitmap.height)
                if (rect.width() <= 0 || rect.height() <= 0) {
                    return@forEach
                }
                val padded = padRect(rect, bitmap.width, bitmap.height)
                val layoutType = block.overlayStyle?.layoutType ?: LayoutType.Unknown
                if (layoutType == LayoutType.Label || layoutType == LayoutType.Bubble) {
                    val fillColor = block.overlayStyle?.bgColorArgb ?: Color.WHITE
                    fillRect(output, padded, fillColor)
                } else {
                    addMask(mask, padded)
                    hasInpaint = true
                }
            }

            if (hasInpaint) {
                val kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(MASK_DILATE_SIZE.toDouble(), MASK_DILATE_SIZE.toDouble())
                )
                Imgproc.dilate(mask, mask, kernel)
                kernel.release()
                Imgproc.inpaint(output, mask, output, INPAINT_RADIUS, Imgproc.INPAINT_TELEA)
            }

            val resultBitmap = Bitmap.createBitmap(
                bitmap.width,
                bitmap.height,
                Bitmap.Config.ARGB_8888,
            )
            Utils.matToBitmap(output, resultBitmap)
            resultBitmap
        } catch (t: Throwable) {
            logger.warn(t = t)
            null
        } finally {
            source.release()
            output.release()
            mask.release()
        }
    }

    private fun ensureOpenCv(): Boolean {
        if (openCvReady) {
            return true
        }
        val loaded = OpenCVLoader.initDebug()
        if (!loaded) {
            logger.warn("OpenCV initDebug failed")
            return false
        }
        openCvReady = true
        return true
    }

    private fun addMask(mask: Mat, rect: Rect) {
        val left = rect.left.toDouble()
        val top = rect.top.toDouble()
        val right = (rect.right - 1).toDouble()
        val bottom = (rect.bottom - 1).toDouble()
        Imgproc.rectangle(mask, Point(left, top), Point(right, bottom), Scalar(255.0), Imgproc.FILLED)
    }

    private fun fillRect(mat: Mat, rect: Rect, color: Int) {
        val left = rect.left.toDouble()
        val top = rect.top.toDouble()
        val right = (rect.right - 1).toDouble()
        val bottom = (rect.bottom - 1).toDouble()
        val scalar = Scalar(
            Color.red(color).toDouble(),
            Color.green(color).toDouble(),
            Color.blue(color).toDouble(),
            255.0,
        )
        Imgproc.rectangle(mat, Point(left, top), Point(right, bottom), scalar, Imgproc.FILLED)
    }

    private fun clampRect(rect: Rect, width: Int, height: Int): Rect {
        val left = rect.left.coerceIn(0, width - 1)
        val top = rect.top.coerceIn(0, height - 1)
        val right = rect.right.coerceIn(left + 1, width)
        val bottom = rect.bottom.coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun padRect(rect: Rect, width: Int, height: Int): Rect {
        val padX = max(MIN_MASK_PAD_PX, (rect.width() * MASK_PAD_RATIO).roundToInt())
        val padY = max(MIN_MASK_PAD_PX, (rect.height() * MASK_PAD_RATIO).roundToInt())
        val left = (rect.left - padX).coerceAtLeast(0)
        val top = (rect.top - padY).coerceAtLeast(0)
        val right = (rect.right + padX).coerceAtMost(width)
        val bottom = (rect.bottom + padY).coerceAtMost(height)
        return Rect(left, top, right, bottom)
    }

    private const val MASK_PAD_RATIO = 0.1f
    private const val MIN_MASK_PAD_PX = 2
    private const val MASK_DILATE_SIZE = 3
    private const val INPAINT_RADIUS = 3.5
}
