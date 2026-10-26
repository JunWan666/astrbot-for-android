package io.astrbot.android.runtime

import io.astrbot.android.requireLocalized
import io.astrbot.android.LocalizedStateException

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PersonaPreset(val id: String, val name: String, val category: String, val intro: String,
    val prompt: String, val greeting: String, val source: String, val sourceUrl: String) {
    val databaseId get() = "安卓预设·$name"
}

internal object PersonaLibrary {
    const val MARKET_URL = "https://jiupamiao.asia/market"
    val categories = listOf("趣味", "陪伴", "效率", "创作")
    private data class Local(val custom: List<PersonaPreset>, val deleted: Set<String>)
    private fun file(context: Context) = File(context.filesDir, "persona-library/catalog.json")

    @Synchronized fun read(context: Context): List<PersonaPreset> = visible(bundled(context), local(context))

    @Synchronized fun add(context: Context, name: String, category: String, intro: String, prompt: String, greeting: String): PersonaPreset {
        val defaults = bundled(context)
        val local = local(context)
        val preset = PersonaPreset("custom-${UUID.randomUUID()}", name.trim(), category, intro.trim(), prompt.trim(), greeting.trim(), "本地添加的人设", "")
        validate(preset)
        requireLocalized(local.custom.size < 200) { AppText.get(R.string.persona_limit) }
        requireLocalized(visible(defaults, local).none { it.name.equals(preset.name, ignoreCase = true) }) { AppText.get(R.string.persona_duplicate) }
        write(context, local.copy(custom = local.custom + preset))
        return preset
    }

    @Synchronized fun delete(context: Context, id: String) {
        val defaults = bundled(context)
        val local = local(context)
        if (visible(defaults, local).none { it.id == id }) return
        write(context, local.copy(custom = local.custom.filterNot { it.id == id },
            deleted = if (defaults.any { it.id == id }) local.deleted + id else local.deleted))
    }

    private fun visible(defaults: List<PersonaPreset>, local: Local) =
        local.custom.asReversed() + defaults.filterNot { it.id in local.deleted }

    private fun local(context: Context): Local {
        val file = file(context)
        if (!DurableFiles.exists(file)) return Local(emptyList(), emptySet())
        return try {
            require(file.length() <= 16L * 1024 * 1024)
            val value = requireNotNull(DurableFiles.read(file))
            require(value.getInt("schema") == 1)
            val custom = value.getJSONArray("custom")
            val deleted = value.getJSONArray("deleted")
            require(custom.length() <= 200 && deleted.length() <= 1000)
            val items = List(custom.length()) { index ->
                val item = custom.getJSONObject(index)
                PersonaPreset(item.getString("id"), item.getString("name"), item.getString("category"), item.getString("intro"),
                    item.getString("prompt"), item.getString("greeting"), "本地添加的人设", "").also {
                    require(it.id.startsWith("custom-")); validate(it)
                }
            }
            require(items.map { it.id }.distinct().size == items.size)
            Local(items, List(deleted.length()) { deleted.getString(it) }.toSet())
        } catch (failure: Exception) {
            throw LocalizedStateException(AppText.get(R.string.persona_read_error), failure)
        }
    }

    private fun write(context: Context, local: Local) {
        val custom = JSONArray()
        local.custom.forEach { preset ->
            custom.put(JSONObject().put("id", preset.id).put("name", preset.name).put("category", preset.category)
                .put("intro", preset.intro).put("prompt", preset.prompt).put("greeting", preset.greeting))
        }
        DurableFiles.write(file(context), JSONObject().put("schema", 1).put("custom", custom)
            .put("deleted", JSONArray(local.deleted.sorted())))
    }

    private fun validate(preset: PersonaPreset) {
        requireLocalized(preset.id.matches(Regex("[a-z0-9-]{1,48}"))) { AppText.get(R.string.persona_invalid_id) }
        requireLocalized(preset.name.length in 1..30 && preset.name.none { it.isISOControl() }) { AppText.get(R.string.persona_invalid_name) }
        requireLocalized(preset.category in categories) { AppText.get(R.string.persona_invalid_category) }
        requireLocalized(preset.prompt.length in 1..12_000) { AppText.get(R.string.persona_invalid_prompt) }
        requireLocalized(preset.intro.length <= 120 && preset.greeting.length <= 1000) { AppText.get(R.string.persona_invalid_intro) }
    }

    private fun bundled(context: Context): List<PersonaPreset> = context.assets.open("personas/catalog.json").bufferedReader().use {
        val json = JSONObject(it.readText())
        require(json.getInt("schema") == 1)
        val items = json.getJSONArray("items")
        List(items.length()) { index ->
            val item = items.getJSONObject(index)
            PersonaPreset(item.getString("id"), item.getString("name"), item.getString("category"), item.getString("intro"),
                item.getString("prompt"), item.getString("greeting"), item.getString("source"), item.getString("sourceUrl"))
                .also { preset ->
                    require(preset.id.matches(Regex("[a-z0-9-]{1,48}")))
                    require(preset.name.length in 1..30 && preset.prompt.length in 20..12_000)
                    require(preset.sourceUrl.startsWith("https://"))
                }
        }.also { presets -> require(presets.map { p -> p.id }.distinct().size == presets.size) }
    }
}

data class PersonaInstallation(val imported: Set<String> = emptySet(), val currentId: String? = null,
    val unavailable: String? = AppText.get(R.string.persona_initialize), val canUndo: Boolean = false)
