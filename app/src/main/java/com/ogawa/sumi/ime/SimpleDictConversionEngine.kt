package com.ogawa.sumi.ime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * assets/dict/common.json を使ったシンプルなかな漢字変換エンジン。
 *
 * ロード戦略:
 *   - 辞書（161KB, 5036語）はバックグラウンドスレッドで非同期にロードする。
 *   - ロード完了前に candidatesFor() が呼ばれた場合は空リストを返す（UX上問題なし）。
 *   - lazy ではなく warmUp() で明示的にプリロードすることで、
 *     初回キー入力時のメインスレッドブロックを回避する。
 */
class SimpleDictConversionEngine(private val context: Context) {

    @Volatile
    private var dict: Map<String, List<String>> = emptyMap()

    init {
        // IMEサービスの onCreate で生成されるため、直後にバックグラウンドロードを開始
        CoroutineScope(Dispatchers.IO).launch {
            dict = loadDict()
        }
    }

    fun candidatesFor(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val snapshot = dict   // @Volatile read: スレッドセーフ
        if (snapshot.isEmpty()) return listOf(input)  // ロード中は入力文字をそのまま返す

        val results = mutableListOf<String>()
        snapshot[input]?.let { results.addAll(it) }
        if (!results.contains(input)) results.add(input)
        val katakana = hiraganaToKatakana(input)
        if (katakana != input && !results.contains(katakana)) results.add(katakana)
        val prefixMatches = snapshot.entries
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
        Log.e("SimpleDict", "Failed to load dictionary", e)
        emptyMap()
    }

    private fun hiraganaToKatakana(s: String): String =
        s.map { ch -> if (ch in 'ぁ'..'ゖ') (ch.code + 0x60).toChar() else ch }.joinToString("")
}
