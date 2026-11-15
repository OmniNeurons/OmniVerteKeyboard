package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * OmniVerte: the user's Gemini API key.
 *
 * Stored AES-GCM-encrypted with a non-exportable Android Keystore key, in its own
 * SharedPreferences file — deliberately not in FUTO's DataStore, whose settings
 * export/backup would carry it along. A restored backup cannot decrypt (the
 * Keystore key never leaves the device), so it simply reads as "no key".
 *
 * No key → voice input behaves exactly like stock FUTO (local Whisper).
 */
object GeminiVoiceKey {
    private const val TAG = "GeminiVoiceKey"
    private const val PREFS = "omniverte_secrets"
    private const val PREF_KEY = "gemini_api_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "omniverte_gemini_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Phase-4 dev drop-in (written over adb run-as); imported once, then deleted. */
    private const val LEGACY_FILE = "gemini_api_key.txt"

    fun read(context: Context): String? = try {
        migrateLegacyFile(context)
        val stored = prefs(context).getString(PREF_KEY, null) ?: return null
        decrypt(stored)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        // e.g. before the first unlock (credential-encrypted storage), or a
        // restored backup whose Keystore key is gone.
        Log.w(TAG, "Gemini key unavailable: ${e.javaClass.simpleName}")
        null
    }

    fun save(context: Context, apiKey: String) {
        val key = apiKey.trim()
        require(key.isNotEmpty())
        prefs(context).edit().putString(PREF_KEY, encrypt(key)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_KEY).apply()
    }

    /** For display: "••••1a2B". */
    fun masked(apiKey: String): String = "••••" + apiKey.takeLast(4)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun migrateLegacyFile(context: Context) {
        val file = File(context.filesDir, LEGACY_FILE)
        if (!file.isFile) return
        val key = file.readText().trim()
        if (key.isNotEmpty()) save(context, key)
        file.delete()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ct)
    }

    private fun decrypt(stored: String): String? {
        val parts = stored.split(":")
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, unb64(parts[0])))
        return String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
