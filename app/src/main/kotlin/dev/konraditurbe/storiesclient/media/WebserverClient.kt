package dev.konraditurbe.storiesclient.media

import dev.konraditurbe.storiesclient.util.abbr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTPS client for the glasses-hosted media webserver (`https://<glasses>/stella-webserver/asset?id=<assetId>`,
 * `Authorization: app_token <token>`; STORIES_MEDIA_PROTOCOL §1-2).
 *
 * The :443 server presents the glasses' self-signed device cert on a link-local WiFi-Direct network, so TLS
 * trust is disabled (the official app does not verify it either). GETs are non-destructive; the webserver's
 * warm-up after `start_webserver` is absorbed by connect-refused retries.
 */
class WebserverClient(private val host: String, private val log: (String) -> Unit = {}) {

    /** Byte-progress sink; [total] is -1 when the server omits Content-Length. */
    fun interface Progress {
        fun onProgress(got: Long, total: Long)
    }

    private val sslFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
    }.socketFactory

    private fun assetUrl(assetId: String) = "https://$host/stella-webserver/asset?id=$assetId"

    /**
     * GET one asset, retrying up to [attempts] times while the webserver is still coming up. Returns the body,
     * or null after the last failure. [stop] aborts the retry loop early (e.g. session torn down).
     */
    fun getAsset(assetId: String, authToken: String?, progress: Progress? = null, attempts: Int = 8, stop: () -> Boolean = { false }): ByteArray? {
        var attempt = 1
        while (attempt <= attempts && !stop()) {
            try {
                return get(assetUrl(assetId), authToken, progress)
            } catch (e: ConnectException) {
                if (attempt == attempts) { log("[http] ${assetId.abbr()} failed: ${e.message}"); return null }
                Thread.sleep(1500)   // webserver still warming up
            } catch (e: Exception) {
                log("[http] ${assetId.abbr()} failed: ${e.message}"); return null
            }
            attempt++
        }
        return null
    }

    /** Single GET with the `app_token` header; throws on transport error or HTTP >= 400. */
    private fun get(urlStr: String, authToken: String?, progress: Progress?): ByteArray {
        val c = URL(urlStr).openConnection() as HttpsURLConnection
        c.sslSocketFactory = sslFactory
        c.setHostnameVerifier { _, _ -> true }
        c.connectTimeout = 8000
        c.readTimeout = 20000
        c.setRequestProperty("Authorization", "app_token $authToken")
        val code = c.responseCode
        val total = c.contentLengthLong
        val input: InputStream? = if (code >= 400) c.errorStream else c.inputStream
        val bo = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var got = 0L
        var lastReport = 0L
        try {
            while (input != null) {
                val n = input.read(tmp)
                if (n <= 0) break
                bo.write(tmp, 0, n); got += n
                if (progress != null && (got - lastReport >= 32768 || (total > 0 && got >= total))) {
                    progress.onProgress(got, total); lastReport = got
                }
            }
        } catch (readEx: Exception) {
            log("[http] read interrupted after ${bo.size()}B: $readEx")
        }
        val body = bo.toByteArray()
        log("[http] $code total=$total got=${body.size}B")
        if (code >= 400) throw IOException("HTTP $code: ${String(body, 0, minOf(body.size, 200), Charsets.ISO_8859_1)}")
        progress?.onProgress(body.size.toLong(), body.size.toLong())   // final 100% (success only)
        return body
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
