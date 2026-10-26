package io.astrbot.android.core

import java.net.URI

data class LocalEndpoint(val port: Int, val host: String = "127.0.0.1", val scheme: String = "http") {
    init {
        require(port in 1024..65535) { "端口须在 1024–65535 之间" }
        require(host in setOf("127.0.0.1", "localhost", "::1")) { "后台入口须使用本机回环地址" }
        require(scheme in setOf("http", "https")) { "后台入口须使用 HTTP 或 HTTPS" }
    }
    val origin: String get() = "$scheme://${if (host == "::1") "[$host]" else host}:$port"

    fun acceptsNavigation(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == scheme && uri.host?.removePrefix("[")?.removeSuffix("]") == host && uri.port == port && uri.userInfo == null
    }.getOrDefault(false)
}
