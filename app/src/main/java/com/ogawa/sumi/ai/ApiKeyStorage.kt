package com.ogawa.sumi.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ApiKeyStorage(context: Context) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "sumi_secure_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    fun saveApiKey(key: String) = prefs.edit().putString("anthropic_api_key", key).apply()
    fun getApiKey(): String? = prefs.getString("anthropic_api_key", null)?.takeIf { it.isNotBlank() }
    fun clearApiKey() = prefs.edit().remove("anthropic_api_key").apply()
    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()
}
