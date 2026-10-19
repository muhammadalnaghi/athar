package com.athar.feature.today

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptImageTextRecognizerInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generatedReceiptUriUsesProductionOfflineAdapter() = runBlocking {
        val image = createGeneratedReceipt(context)
        try {
            val result = ReceiptImageTextRecognizer(context).recognize(Uri.fromFile(image))
            assertThat(result).contains("HERMES QA RECEIPT")
            assertThat(result).contains("SAMPLE STORE")
            assertThat(result).contains("TOTAL SAR 42.50")
        } finally {
            image.delete()
        }
    }

    @Test
    fun invalidImageFailsThroughProductionAdapter() = runBlocking {
        val invalid = File.createTempFile("not-receipt", ".txt", context.cacheDir)
        invalid.writeText("not an image")
        try {
            val failure = runCatching {
                ReceiptImageTextRecognizer(context).recognize(Uri.fromFile(invalid))
            }.exceptionOrNull()
            assertThat(failure).isNotNull()
        } finally {
            invalid.delete()
        }
    }

    @Test
    fun corruptInstalledPublicModelIsReplacedAtomically() = runBlocking {
        val installer = OfflineOcrModelInstaller(context)
        val dataPath = installer.ensureInstalled()
        val model = dataPath.resolve(OfflineOcrModelInstaller.MODEL_RELATIVE_PATH)
        model.writeBytes(byteArrayOf(1, 2, 3))

        installer.ensureInstalled()

        assertThat(sha256(model)).isEqualTo(OfflineOcrModelInstaller.EXPECTED_MODEL_SHA256)
        assertThat(File(model.parentFile, "${model.name}.new").exists()).isFalse()
        assertThat(File(model.parentFile, "${model.name}.bak").exists()).isFalse()
    }

    @Test
    fun concurrentFreshInstallersUseOneAtomicPublicModel() = runBlocking {
        val model = context.filesDir
            .resolve(OfflineOcrModelInstaller.DATA_DIRECTORY)
            .resolve(OfflineOcrModelInstaller.MODEL_RELATIVE_PATH)
        AtomicFile(model).delete()

        val installers = List(4) { OfflineOcrModelInstaller(context) }
        val paths = coroutineScope {
            installers.map { installer ->
                async(Dispatchers.IO) { installer.ensureInstalled() }
            }.awaitAll()
        }

        assertThat(paths.map { it.absolutePath }.toSet()).hasSize(1)
        assertThat(sha256(model)).isEqualTo(OfflineOcrModelInstaller.EXPECTED_MODEL_SHA256)
        assertThat(File(model.parentFile, "${model.name}.bak").exists()).isFalse()
    }

    @Test
    fun silentAtomicCommitFailureIsRejectedAndNextInstallRetries() = runBlocking {
        val model = context.filesDir
            .resolve(OfflineOcrModelInstaller.DATA_DIRECTORY)
            .resolve(OfflineOcrModelInstaller.MODEL_RELATIVE_PATH)
        AtomicFile(model).delete()
        var failCommit = true
        val installer = OfflineOcrModelInstaller(context) { file, stream ->
            file.finishWrite(stream)
            if (failCommit) {
                failCommit = false
                file.baseFile.writeBytes(byteArrayOf(1, 2, 3))
            }
        }
        val failure = runCatching { installer.ensureInstalled() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        installer.ensureInstalled()
        assertThat(sha256(model)).isEqualTo(OfflineOcrModelInstaller.EXPECTED_MODEL_SHA256)
    }

    @Test
    fun cancellationStillRecyclesBitmapAndNativeOwner() = runBlocking {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val cancellation = CancellationException("synthetic native cancellation")
        val engine = RecordingEngine(cancellation)
        val recognizer = ReceiptImageTextRecognizer(
            modelInstaller = OfflineOcrModelInstaller(context),
            bitmapDecoder = ReceiptBitmapDecoder { bitmap },
            engineFactory = ReceiptOcrEngineFactory { engine },
        )

        val failure = runCatching {
            recognizer.recognize(Uri.EMPTY)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(engine.recycled).isTrue()
        assertThat(bitmap.isRecycled).isTrue()
    }

    @Test
    fun failedInitializationStillRecyclesBitmapAndNativeOwner() = runBlocking {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val engine = RecordingEngine(null, initialize = false)
        val recognizer = ReceiptImageTextRecognizer(
            modelInstaller = OfflineOcrModelInstaller(context),
            bitmapDecoder = ReceiptBitmapDecoder { bitmap },
            engineFactory = ReceiptOcrEngineFactory { engine },
        )
        val failure = runCatching { recognizer.recognize(Uri.EMPTY) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(engine.recycled).isTrue()
        assertThat(bitmap.isRecycled).isTrue()
    }

    @Test
    fun productionDecoderBoundsLargeImageDimensionsAndPixels() {
        val image = File.createTempFile("bounded-image", ".png", context.cacheDir)
        val source = Bitmap.createBitmap(3000, 2200, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.WHITE)
            image.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            source.recycle()
        }
        try {
            val bitmap = AndroidReceiptBitmapDecoder(context).decode(Uri.fromFile(image))
            try {
                assertThat(bitmap.width).isAtMost(2048)
                assertThat(bitmap.height).isAtMost(2048)
                assertThat(bitmap.width.toLong() * bitmap.height).isAtMost(4_000_000L)
                assertThat(bitmap.config).isEqualTo(Bitmap.Config.ARGB_8888)
            } finally {
                bitmap.recycle()
            }
        } finally {
            image.delete()
        }
    }

    @Test
    fun productionDecoderAppliesExifOrientationOnce() {
        val width = 320
        val height = 200
        val image = File.createTempFile("oriented-image", ".jpg", context.cacheDir)
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.WHITE)
            image.outputStream().use { check(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally {
            source.recycle()
        }
        try {
            ExifInterface(image.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val bitmap = AndroidReceiptBitmapDecoder(context).decode(Uri.fromFile(image))
            try {
                assertThat(bitmap.width).isEqualTo(height)
                assertThat(bitmap.height).isEqualTo(width)
            } finally {
                bitmap.recycle()
            }
        } finally {
            image.delete()
        }
    }

    @Test
    fun productionDecoderConvertsVerifiedWideGamutInputToSoftwareSrgb() {
        val image = File.createTempFile("wide-color-image", ".png", context.cacheDir)
        val source = Bitmap.createBitmap(
            320, 200, Bitmap.Config.RGBA_F16, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
        )
        try {
            source.eraseColor(Color.WHITE)
            image.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            source.recycle()
        }
        try {
            var sourceIsWideGamut = false
            val unconverted = ImageDecoder.decodeBitmap(ImageDecoder.createSource(image)) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                sourceIsWideGamut = info.colorSpace?.isWideGamut == true
            }
            unconverted.recycle()
            assertThat(sourceIsWideGamut).isTrue()
            val bitmap = AndroidReceiptBitmapDecoder(context).decode(Uri.fromFile(image))
            try {
                assertThat(bitmap.config).isEqualTo(Bitmap.Config.ARGB_8888)
                assertThat(bitmap.colorSpace).isEqualTo(ColorSpace.get(ColorSpace.Named.SRGB))
            } finally {
                bitmap.recycle()
            }
        } finally {
            image.delete()
        }
    }

    private fun createGeneratedReceipt(context: Context): File {
        val file = File.createTempFile("generated-receipt", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textSize = 64f
            }
            canvas.drawText("HERMES QA RECEIPT", 80f, 150f, paint)
            canvas.drawText("SAMPLE STORE", 80f, 270f, paint)
            canvas.drawText("2026-10-18 12:30", 80f, 390f, paint)
            canvas.drawText("TOTAL SAR 42.50", 80f, 570f, paint)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private class RecordingEngine(
        private val failure: Throwable?,
        private val initialize: Boolean = true,
    ) : ReceiptOcrEngine {
        var recycled: Boolean = false

        override fun init(dataPath: String): Boolean = initialize

        override fun setImage(bitmap: Bitmap) = Unit

        override fun getText(): String {
            failure?.let { throw it }
            return "unused"
        }

        override fun recycle() {
            recycled = true
        }
    }
}
