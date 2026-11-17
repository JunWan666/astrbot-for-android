package io.astrbot.android.runtime

import io.astrbot.android.AppText
import io.astrbot.android.R
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Records no URL paths, queries, response bodies, headers, or raw exception messages. */
internal class UpdateRequestFailure(val phase: String, val host: String, val status: Int? = null, cause: IOException? = null) :
    IOException("Update request failed: phase=$phase host=$host status=${status ?: "none"}", cause)

internal object UpdateNetworkDiagnostics {
    private fun causes(failure: Throwable): List<Throwable> = generateSequence(failure) { it.cause }.take(8).toList()

    fun record(action: String, failure: Throwable): String {
        val chain = causes(failure)
        val request = chain.filterIsInstance<UpdateRequestFailure>().firstOrNull()
        return "update-failure action=$action phase=${request?.phase ?: "operation"} host=${request?.host ?: "unknown"} " +
            "http=${request?.status ?: "none"} types=${chain.joinToString(",") { it.javaClass.simpleName }}"
    }

    fun message(failure: Throwable): String? {
        val chain = causes(failure)
        val request = chain.filterIsInstance<UpdateRequestFailure>().firstOrNull()
        return when {
            request?.status != null -> AppText.get(R.string.update_network_http, request.status)
            chain.any { it is UnknownHostException } -> AppText.get(R.string.update_network_dns)
            chain.any { it is SocketTimeoutException || it is java.io.InterruptedIOException && it.message == "timeout" } -> AppText.get(R.string.update_network_timeout)
            chain.any { it is SSLException } -> AppText.get(R.string.update_network_tls)
            else -> null
        }
    }
}
