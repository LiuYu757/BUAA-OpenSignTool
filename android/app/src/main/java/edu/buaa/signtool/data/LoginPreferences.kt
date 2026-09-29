package edu.buaa.signtool.data

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

data class SavedLogin(
    val username: String,
    val password: String,
    val networkMode: NetworkMode,
)

/** Stores the optional quick-login profile with an Android Keystore-backed key. */
class LoginPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): SavedLogin? {
        val username = prefs.getString(KEY_USERNAME, null).orEmpty()
        val encrypted = prefs.getString(KEY_PASSWORD, null).orEmpty()
        val iv = prefs.getString(KEY_IV, null).orEmpty()
        if (username.isBlank() || encrypted.isBlank() || iv.isBlank()) return null
        val password = runCatching { decrypt(encrypted, iv) }.getOrNull() ?: return null
        val mode = runCatching {
            NetworkMode.valueOf(prefs.getString(KEY_MODE, NetworkMode.WEB_VPN.name).orEmpty())
        }.getOrDefault(NetworkMode.WEB_VPN)
        return SavedLogin(username, password, mode)
    }

    fun save(username: String, password: String, networkMode: NetworkMode) {
        val encrypted = encrypt(password)
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, encrypted.first)
            .putString(KEY_IV, encrypted.second)
            .putString(KEY_MODE, networkMode.name)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) to
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decrypt(value: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "login_profile"
        const val KEY_ALIAS = "buaa_sign_login_key"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password_ciphertext"
        const val KEY_IV = "password_iv"
        const val KEY_MODE = "network_mode"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
