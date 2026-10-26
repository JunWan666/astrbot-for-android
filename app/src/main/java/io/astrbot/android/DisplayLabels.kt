package io.astrbot.android

/** Stored category names and component keys are independent of the interface language. */
internal fun personaCategoryLabel(key: String): String = when (key) {
    "" -> AppText.get(R.string.persona_category_all)
    "趣味" -> AppText.get(R.string.persona_category_fun)
    "陪伴" -> AppText.get(R.string.persona_category_company)
    "效率" -> AppText.get(R.string.persona_category_productivity)
    "创作" -> AppText.get(R.string.persona_category_creative)
    else -> key
}

internal fun componentLabel(key: String): String = when (key) {
    "运行环境" -> AppText.get(R.string.component_runtime)
    "架构" -> AppText.get(R.string.component_architecture)
    else -> key
}
