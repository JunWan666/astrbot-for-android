package io.astrbot.android

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Identifies messages written by this app without relying on their language. */
internal interface LocalizedFailure
internal class LocalizedStateException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause), LocalizedFailure
internal class LocalizedArgumentException(message: String) : IllegalArgumentException(message), LocalizedFailure

@OptIn(ExperimentalContracts::class)
internal inline fun checkLocalized(value: Boolean, message: () -> String) {
    contract { returns() implies value }
    if (!value) throw LocalizedStateException(message())
}

@OptIn(ExperimentalContracts::class)
internal inline fun requireLocalized(value: Boolean, message: () -> String) {
    contract { returns() implies value }
    if (!value) throw LocalizedArgumentException(message())
}

@OptIn(ExperimentalContracts::class)
internal inline fun <T : Any> requireNotNullLocalized(value: T?, message: () -> String): T {
    contract { returns() implies (value != null) }
    return value ?: throw LocalizedArgumentException(message())
}

@OptIn(ExperimentalContracts::class)
internal inline fun <T : Any> checkNotNullLocalized(value: T?, message: () -> String): T {
    contract { returns() implies (value != null) }
    return value ?: throw LocalizedStateException(message())
}

internal fun Throwable.userFacingMessage(): String? = message?.takeIf {
    this is LocalizedFailure || it.any { char -> char in '\u4e00'..'\u9fff' }
}
