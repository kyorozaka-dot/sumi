package com.ogawa.sumi.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "keyboard_settings"
)

/**
 * キーボード設定の型付きラッパー。
 *
 *   - 読み取り: `snapshot: Flow<Snapshot>` を collectAsState で観測
 *   - 書き込み: `set...()` を coroutine から呼ぶ
 *
 * 値は DataStore（Preferences）に永続化される。
 */
class KeyboardPreferences(private val context: Context) {

    object Keys {
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_TONE = stringPreferencesKey("ai_tone")            // "auto" | "casual" | "polite" | "formal"
        val AI_COUNT = intPreferencesKey("ai_count")             // 1-5
        val AI_ALLOW_CLOUD = booleanPreferencesKey("ai_cloud")   // クラウドAPI送信許可
        val FLICK_SENSITIVITY = intPreferencesKey("flick_sens")  // 0-100、UIスライダー
        val LONG_PRESS_MS = intPreferencesKey("longpress_ms")    // 150-600
        val HAPTIC = booleanPreferencesKey("haptic")
        val THEME = stringPreferencesKey("theme")                // "auto" | "light" | "dark"
        val KEYBOARD_HEIGHT = intPreferencesKey("kbd_height")    // 40-100 (%)
    }

    /** すべての設定をひとまとめにしたイミュータブルスナップショット */
    data class Snapshot(
        val aiEnabled: Boolean = true,
        val aiTone: String = "auto",
        val aiCount: Int = 3,
        val aiAllowCloud: Boolean = false,
        val flickSensitivity: Int = 50,
        val longPressMs: Int = 250,
        val haptic: Boolean = true,
        val theme: String = "auto",
        val keyboardHeight: Int = 60
    )

    val snapshot: Flow<Snapshot> = context.dataStore.data.map { p ->
        Snapshot(
            aiEnabled = p[Keys.AI_ENABLED] ?: true,
            aiTone = p[Keys.AI_TONE] ?: "auto",
            aiCount = (p[Keys.AI_COUNT] ?: 3).coerceIn(1, 5),
            aiAllowCloud = p[Keys.AI_ALLOW_CLOUD] ?: false,
            flickSensitivity = (p[Keys.FLICK_SENSITIVITY] ?: 50).coerceIn(0, 100),
            longPressMs = (p[Keys.LONG_PRESS_MS] ?: 250).coerceIn(150, 600),
            haptic = p[Keys.HAPTIC] ?: true,
            theme = p[Keys.THEME] ?: "auto",
            keyboardHeight = (p[Keys.KEYBOARD_HEIGHT] ?: 60).coerceIn(40, 100)
        )
    }

    suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setInt(key: Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { it[key] = value }
    }

    /** TODO: 学習データが実装されたら、ここで削除する */
    suspend fun clearLearningData() = Unit

    /** TODO: 入力履歴が実装されたら、ここで削除する */
    suspend fun clearInputHistory() = Unit
}
