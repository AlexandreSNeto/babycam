package com.babycam.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.babycam.app.model.CameraConfig

/**
 * Encrypted persistence for the single [CameraConfig] this app manages, plus the
 * standalone mute flag. Backed by [EncryptedSharedPreferences] (Android Keystore).
 */
class CameraConfigStore(
    context: Context,
    prefsFileName: String = "camera_config",
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        prefsFileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getCamera(): CameraConfig? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        return CameraConfig(
            host = host,
            port = prefs.getInt(KEY_PORT, 554),
            path = prefs.getString(KEY_PATH, "") ?: "",
            username = prefs.getString(KEY_USERNAME, null),
            password = prefs.getString(KEY_PASSWORD, null),
        )
    }

    fun saveCamera(config: CameraConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_PATH, config.path)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .apply()
    }

    fun deleteCamera() {
        prefs.edit()
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .remove(KEY_PATH)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .apply()
    }

    fun getMuted(): Boolean = prefs.getBoolean(KEY_MUTED, false)

    fun setMuted(muted: Boolean) {
        prefs.edit().putBoolean(KEY_MUTED, muted).apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_PATH = "path"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_MUTED = "muted"
    }
}
