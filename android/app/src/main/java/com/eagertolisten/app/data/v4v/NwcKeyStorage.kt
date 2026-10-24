package com.eagertolisten.app.data.v4v

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class NwcKeyStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences("eager_nwc_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "EagerNwcSecretKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        private const val PREF_WALLET_PUBKEY = "nwc_wallet_pubkey"
        private const val PREF_RELAY_URL = "nwc_relay_url"
        private const val PREF_LUD16 = "nwc_lud16"
        private const val PREF_ENCRYPTED_SECRET = "nwc_encrypted_secret"
        private const val PREF_IV = "nwc_secret_iv"
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun saveConnection(connection: NwcConnection): Boolean {
        return try {
            val key = try {
                getOrCreateSecretKey()
            } catch (_: Throwable) {
                null
            }

            if (key != null) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                val encrypted = cipher.doFinal(connection.secret.toByteArray(Charsets.UTF_8))

                prefs.edit()
                    .putString(PREF_WALLET_PUBKEY, connection.walletPubkey)
                    .putString(PREF_RELAY_URL, connection.relayUrl)
                    .putString(PREF_LUD16, connection.lud16)
                    .putString(PREF_ENCRYPTED_SECRET, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply()
            } else {
                prefs.edit()
                    .putString(PREF_WALLET_PUBKEY, connection.walletPubkey)
                    .putString(PREF_RELAY_URL, connection.relayUrl)
                    .putString(PREF_LUD16, connection.lud16)
                    .putString(PREF_ENCRYPTED_SECRET, connection.secret)
                    .apply()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getConnection(): NwcConnection? {
        val pubkey = prefs.getString(PREF_WALLET_PUBKEY, null) ?: return null
        val relay = prefs.getString(PREF_RELAY_URL, null) ?: return null
        val lud16 = prefs.getString(PREF_LUD16, null)
        val encSecret = prefs.getString(PREF_ENCRYPTED_SECRET, null) ?: return null
        val ivString = prefs.getString(PREF_IV, null)

        val secret = if (ivString != null) {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val iv = Base64.decode(ivString, Base64.NO_WRAP)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, entry.secretKey, spec)
                val decrypted = cipher.doFinal(Base64.decode(encSecret, Base64.NO_WRAP))
                String(decrypted, Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
        } else {
            encSecret
        }

        return NwcConnection(pubkey, relay, secret, lud16)
    }

    fun clearConnection() {
        prefs.edit().clear().apply()
    }

    fun hasConnection(): Boolean = prefs.contains(PREF_WALLET_PUBKEY)
}
