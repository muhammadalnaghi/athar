package com.athar.feature.today

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

private const val MAX_IMAGE_DIMENSION = 2048
private const val MAX_IMAGE_PIXELS = 4_000_000L

internal fun interface ReceiptBitmapDecoder {
    fun decode(uri: Uri): Bitmap
}

internal interface ReceiptOcrEngine {
    fun init(dataPath: String): Boolean
    fun setImage(bitmap: Bitmap)
    fun getText(): String
    fun recycle()
}

internal fun interface ReceiptOcrEngineFactory {
    fun create(): ReceiptOcrEngine
}

class ReceiptImageTextRecognizer internal constructor(
    private val modelInstaller: OfflineOcrModelInstaller,
    private val bitmapDecoder: ReceiptBitmapDecoder,
    private val engineFactory: ReceiptOcrEngineFactory,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        modelInstaller = OfflineOcrModelInstaller(context),
        bitmapDecoder = AndroidReceiptBitmapDecoder(context),
        engineFactory = ReceiptOcrEngineFactory { TesseractReceiptOcrEngine() },
        workerDispatcher = Dispatchers.Default,
    )

    suspend fun recognize(uri: Uri): String = withContext(workerDispatcher) {
        currentCoroutineContext().ensureActive()
        val dataPath = modelInstaller.ensureInstalled()
        currentCoroutineContext().ensureActive()
        val bitmap = bitmapDecoder.decode(uri)
        try {
            currentCoroutineContext().ensureActive()
            val engine = engineFactory.create()
            try {
                currentCoroutineContext().ensureActive()
                if (!engine.init(dataPath.absolutePath)) {
                    throw IOException("Offline OCR engine initialization failed")
                }
                currentCoroutineContext().ensureActive()
                engine.setImage(bitmap)
                currentCoroutineContext().ensureActive()
                val text = engine.getText()
                currentCoroutineContext().ensureActive()
                text.trim()
            } finally {
                engine.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

internal class AndroidReceiptBitmapDecoder(
    private val context: Context,
) : ReceiptBitmapDecoder {

    override fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            if (width <= 0 || height <= 0) {
                throw IOException("Receipt image has no decodable dimensions")
            }
            val target = boundedTargetSize(width, height)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(target.first, target.second)
            // ImageDecoder applies normal EXIF orientation; do not rotate twice.
        }
        return normalizeForTesseract(decoded)
    }
}

private fun normalizeForTesseract(bitmap: Bitmap): Bitmap {
    val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
    if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace == srgb) {
        return bitmap
    }

    val normalized = try {
        Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888,
            true,
            srgb,
        )
    } catch (throwable: Throwable) {
        bitmap.recycle()
        throw throwable
    }

    return try {
        Canvas(normalized).drawBitmap(bitmap, 0f, 0f, null)
        normalized
    } catch (throwable: Throwable) {
        normalized.recycle()
        throw throwable
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private fun boundedTargetSize(width: Int, height: Int): Pair<Int, Int> {
    val dimensionScale = min(
        1.0,
        MAX_IMAGE_DIMENSION.toDouble() / maxOf(width, height).toDouble(),
    )
    val pixelScale = min(
        1.0,
        sqrt(MAX_IMAGE_PIXELS.toDouble() / (width.toDouble() * height.toDouble())),
    )
    val scale = min(dimensionScale, pixelScale)
    return Pair(
        floor(width * scale).toInt().coerceAtLeast(1),
        floor(height * scale).toInt().coerceAtLeast(1),
    )
}

private class TesseractReceiptOcrEngine : ReceiptOcrEngine {
    private val delegate = TessBaseAPI()

    override fun init(dataPath: String): Boolean =
        delegate.init(dataPath, OfflineOcrModelInstaller.LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY)

    override fun setImage(bitmap: Bitmap) {
        delegate.setImage(bitmap)
    }

    override fun getText(): String = delegate.getUTF8Text() ?: ""

    override fun recycle() {
        // Recycling is performed on this execution context after native calls return.
        delegate.recycle()
    }
}
