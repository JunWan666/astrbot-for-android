package io.astrbot.android

/** Stored component keys are independent of the interface language. */
internal fun componentLabel(key: String): String = when (key) {
    "运行环境" -> AppText.get(R.string.component_runtime)
    "架构" -> AppText.get(R.string.component_architecture)
    else -> key
}
