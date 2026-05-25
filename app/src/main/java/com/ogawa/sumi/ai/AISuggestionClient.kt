package com.ogawa.sumi.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ============================================================================
// データクラス
// ============================================================================

data class SuggestionContext(
    val textBeforeCursor: String,
    val textAfterCursor: String,
    val composingText: String,
    val appPackage: String,
    val tonePreference: String // "auto" | "casual" | "polite" | "formal"
)

@Serializable
data class AISuggestion(
    val text: String,
    val tone: String = "casual",
    val intent: String = "continue"
)

@Serializable
private data class AISuggestionResponse(
    val suggestions: List<AISuggestion>
)

// ============================================================================
// クライアント
// ============================================================================

/**
 * AI返信候補生成のクライアント。
 *
 * 実装方針:
 *   1. 端末内モデル (Gemini Nano via AICore) があれば優先
 *   2. なければ Claude API（要ユーザー同意）
 *   3. ネットワーク不通・タイムアウト時は空配列
 *
 * このスケルトンでは Claude API パスのみ実装。
 * 端末内モデル統合は本番で AICore SDK を追加して差し替える。
 */
class AISuggestionClient(@Suppress("unused") private val context: Context) {

    private val cache = SuggestionCache(maxEntries = 32, ttlMillis = 30_000)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 文脈に基づいて候補を3件取得する。失敗時は空リスト。
     */
    suspend fun suggest(ctx: SuggestionContext): List<AISuggestion> {
        // 1) サニタイズ
        val sanitized = ctx.copy(
            textBeforeCursor = sanitize(ctx.textBeforeCursor),
            textAfterCursor = sanitize(ctx.textAfterCursor)
        )

        // 2) キャッシュ確認
        val key = sanitized.cacheKey()
        cache.get(key)?.let { return it }

        // 3) 実呼び出し
        val result = runCatching {
            withContext(Dispatchers.IO) { callClaude(sanitized) }
        }.getOrElse {
            emptyList()
        }

        if (result.isNotEmpty()) cache.put(key, result)
        return result
    }

    // ------------------------------------------------------------------------
    // Claude API 呼び出し
    // ------------------------------------------------------------------------

    private fun callClaude(ctx: SuggestionContext): List<AISuggestion> {
        val apiKey = readApiKeyOrNull() ?: return emptyList()

        val payload = buildClaudeRequest(ctx)
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            return parseClaudeResponse(body)
        }
    }

    private fun buildClaudeRequest(ctx: SuggestionContext): String {
        // Claude Messages API のリクエスト形式
        // system プロンプトは prompt-design.md と同じ内容を使う
        val systemPrompt = SYSTEM_PROMPT
        val userPrompt = buildUserPrompt(ctx)

        // 手書きJSON（kotlinx.serialization使用でも可。最小依存にしたいのでここでは文字列組み立て）
        return """
            {
              "model": "claude-haiku-4-5-20251001",
              "max_tokens": 400,
              "system": ${quoteJsonString(systemPrompt)},
              "messages": [
                {"role": "user", "content": ${quoteJsonString(userPrompt)}}
              ]
            }
        """.trimIndent()
    }

    private fun buildUserPrompt(ctx: SuggestionContext): String {
        return buildString {
            append("【会話直前のメッセージ】\n")
            append(ctx.textBeforeCursor.takeLast(500))
            append("\n\n【ユーザーが入力中のテキスト】\n")
            append(ctx.composingText.ifEmpty { "(なし)" })
            append("\n\n【コンテキスト】\n")
            append("アプリ: ${ctx.appPackage}\n")
            append("トーン指定: ${ctx.tonePreference}\n")
            append("\n上記の文脈から、返信候補を3つ JSON で生成してください。")
        }
    }

    private fun parseClaudeResponse(body: String): List<AISuggestion> {
        // Claude のレスポンス → content[0].text の中に JSON が入る
        // 簡易実装: text 部分を抜き出して AISuggestionResponse でパース
        val textBlock = extractTextBlock(body) ?: return emptyList()
        val cleaned = textBlock.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            json.decodeFromString(AISuggestionResponse.serializer(), cleaned).suggestions.take(3)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractTextBlock(body: String): String? {
        // 安直な抽出: "text":"..." の最初を取る。本番は kotlinx.serialization で構造的にパース推奨
        val marker = "\"text\":\""
        val start = body.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val sb = StringBuilder()
        var i = from
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(body[i + 1])
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    private fun readApiKeyOrNull(): String? {
        // TODO: 本番は EncryptedSharedPreferences / Android Keystore で保存
        // ここではダミー実装
        return null
    }

    // ------------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------------

    private fun sanitize(text: String): String = text
        .replace(Regex("""\b0\d{1,4}-?\d{1,4}-?\d{3,4}\b"""), "[NUM]")
        .replace(Regex("""\b[\w.-]+@[\w.-]+\.\w+\b"""), "[MAIL]")
        .replace(Regex("""https?://\S+"""), "[URL]")

    private fun quoteJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    private fun SuggestionContext.cacheKey(): String =
        "$textBeforeCursor|$composingText|$tonePreference"

    companion object {
        private const val SYSTEM_PROMPT = """
あなたは日本語スマートフォンキーボードに組み込まれたAI返信候補生成エンジンです。
会話の文脈とユーザーが入力中のテキストから、自然な返信候補を3つ提案します。

【絶対遵守】
1. 出力はJSON形式のみ。前置き・説明文・コードブロック記号は含めない
2. 候補は必ず3つ、各5〜35文字
3. 3候補は別方向の返答にする（受ける/断る/保留など分散）
4. 会話の語調（敬語/カジュアル）を相手のメッセージから推定し合わせる
5. composingがある場合はその続きとして自然な内容にする
6. 個人情報の生成禁止（電話番号・住所・メール・URL・金額）
7. 不確実な事実（時刻・約束・固有名詞）は確認形にする
8. 絵文字は相手が使っている場合のみ、ビジネス文脈では使わない

【出力スキーマ】
{"suggestions":[{"text":"...","tone":"casual|polite|formal","intent":"accept|decline|defer|clarify|agree|disagree|continue|close|greet|thank"}]}
"""
    }
}

// ============================================================================
// シンプルなLRUキャッシュ + TTL
// ============================================================================

private class SuggestionCache(
    private val maxEntries: Int,
    private val ttlMillis: Long
) {
    private data class CacheEntry(val value: List<AISuggestion>, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, CacheEntry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: String): List<AISuggestion>? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: String, value: List<AISuggestion>) {
        map[key] = CacheEntry(value, System.currentTimeMillis() + ttlMillis)
    }
}
