package com.mozhi.reader.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("DEPRECATION")
@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun put(alias: String, apiKey: String) {
        preferences.edit { putString(alias, apiKey) }
    }

    fun get(alias: String): String? = preferences.getString(alias, null)

    fun remove(alias: String) {
        preferences.edit { remove(alias) }
    }

    /** Atomically claim a legacy shared credential for the provider active before switching. */
    @Synchronized
    fun migrateAlias(oldAlias: String, newAlias: String): String? {
        val current = get(newAlias)
        val legacy = get(oldAlias)
        if (legacy != null) {
            val saved = preferences.edit().apply {
                if (current == null) putString(newAlias, legacy)
                remove(oldAlias)
            }.commit()
            check(saved) { "保存 API Key 失败，请重试" }
        }
        return current ?: legacy
    }

    private companion object {
        const val FILE_NAME = "encrypted_ai_credentials"
    }
}
