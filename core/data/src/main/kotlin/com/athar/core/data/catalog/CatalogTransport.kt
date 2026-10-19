package com.athar.core.data.catalog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A request prepared without starting I/O; cancellation is safe before execute(). */
internal interface CatalogPreparedRequest {
    suspend fun execute(): ByteArray
    suspend fun cancel()
}

/**
 * Nonfinancial transport seam. The default adapter keeps existing fakes usable,
 * while production overrides [prepare] with a real not-yet-enqueued OkHttp Call.
 */
internal interface CatalogTransport {
    suspend fun fetch(): ByteArray
    suspend fun cancelActive()

    fun prepare(): CatalogPreparedRequest = object : CatalogPreparedRequest {
        override suspend fun execute(): ByteArray = fetch()
        override suspend fun cancel() = cancelActive()
    }
}

/** Thrown when the device/flavor cannot make the request at all (no INTERNET). */
internal class CatalogTransportUnavailableException(message: String) : IOException(message)

/**
 * The single fixed-endpoint HTTPS GET for the signed public catalog. Calls are
 * constructed before the repository's consent/control lock is released, then
 * enqueued only after registration. OkHttp cancellation is tied to coroutine
 * cancellation and to the prepared request handle.
 */
@Singleton
internal class HttpCatalogTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : CatalogTransport {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val standaloneControl = Mutex()
    private var standaloneRequest: CatalogPreparedRequest? = null

    override fun prepare(): CatalogPreparedRequest {
        if (context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            throw CatalogTransportUnavailableException("INTERNET permission not granted")
        }
        val call = client.newCall(
            Request.Builder()
                .url(CATALOG_ENDPOINT)
                .get()
                .header("User-Agent", CATALOG_USER_AGENT)
                .build(),
        )
        return OkHttpCatalogPreparedRequest(call)
    }

    override suspend fun fetch(): ByteArray {
        val request = prepare()
        standaloneControl.withLock { standaloneRequest = request }
        return try {
            request.execute()
        } finally {
            standaloneControl.withLock {
                if (standaloneRequest === request) standaloneRequest = null
            }
        }
    }

    override suspend fun cancelActive() {
        standaloneControl.withLock {
            standaloneRequest?.cancel()
            standaloneRequest = null
        }
    }

    private class OkHttpCatalogPreparedRequest(
        private val call: Call,
    ) : CatalogPreparedRequest {
        override suspend fun execute(): ByteArray = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val bytes = response.use(::readBounded)
                            if (continuation.isActive) continuation.resume(bytes)
                        } catch (failure: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                })
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }

        override suspend fun cancel() {
            call.cancel()
        }

        private fun readBounded(response: Response): ByteArray {
            if (response.code != 200) {
                throw IOException("catalog endpoint returned HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("catalog endpoint returned no body")
            val source = body.source()
            val buffer = Buffer()
            var total = 0L
            while (true) {
                val read = source.read(buffer, 8 * 1024)
                if (read == -1L) break
                total += read
                if (total > CatalogEnvelopeParser.MAX_ENVELOPE_BYTES) {
                    throw IOException("catalog response exceeds envelope bound")
                }
            }
            return buffer.readByteArray()
        }
    }

    companion object {
        const val CATALOG_ENDPOINT: String =
            "https://raw.githubusercontent.com/wa1939/athar/merchant-catalog/catalog-v1.json"
        const val CATALOG_USER_AGENT: String = "Athar-Merchant-Catalog/1"
    }
}
