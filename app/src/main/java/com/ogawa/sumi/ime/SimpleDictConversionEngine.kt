package com.ogawa.sumi.ime

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class SimpleDictConversionEngine(private val context: Context) {
    private val dict: Map<String, List<String>> by lazy { loadDict() }

    fun candidatesFor(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val results = mutableListOf<String>()
        dict[input]?.let { results.addAll(it) }
        if (!results.contains(input)) results.add(input)
        val katakana = hiraganaToKatakana(input)
        if (katakana != input && !results.contains(katakana)) results.add(katakana)
        val prefixMatches = dict.entries
            .filter { input.startsWith(it.key) && it.key != input }
            .flatMap { entry ->
                val remaining = input.removePrefix(entry.key)
                entry.value.map { it + remaining }
            }
            .take(3)
        results.addAll(prefixMatches)
        return results.distinct().take(8)
    }

    private fun loadDict(): Map<String, List<String>> = try {
        val text = context.assets.open("dict/common.json").bufferedReader().use { it.readText() }
        val parsed = Json.parseToJsonElement(text) as JsonObject
        parsed.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
    } catch (e: Exception) {
        android.util.Log.e("SimpleDict", "Failed to load dictionary", e)
        emptyMap()
    }

    private fun hiraganaToKatakana(s: String): String =
        s.map { ch -> if (ch in 'ぁ'..'ゖ') (ch.code + 0x60).toChar() else ch }.joinToString("")
}
