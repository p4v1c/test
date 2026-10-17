package fr.assistantvocal.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import fr.assistantvocal.core.settings.KeyValueStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Réglages ordinaires dans les SharedPreferences. */
class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    constructor(context: Context) : this(context.getSharedPreferences("assistant", Context.MODE_PRIVATE))

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** Secrets (clés API). Jamais stockés en clair. */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String?)
    fun has(name: String): Boolean = !get(name).isNullOrBlank()
}

class InMemorySecrets : SecretStore {
    private val map = mutableMapOf<String, String>()
    override fun get(name: String) = map[name]
    override fun put(name: String, value: String?) { if (value.isNullOrBlank()) map.remove(name) else map[name] = value }
}

/**
 * Chiffrement AES-GCM avec une clé non exportable de l'Android Keystore.
 * Remplace EncryptedSharedPreferences (déprécié par Google) avec la même garantie :
 * les clés API ne sont jamais écrites en clair sur le stockage.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    private companion object {
        const val ALIAS = "assistant-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
