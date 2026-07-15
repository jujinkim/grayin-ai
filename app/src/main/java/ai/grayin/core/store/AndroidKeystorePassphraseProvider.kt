package ai.grayin.core.store

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface StorePassphraseProvider {
    fun getPassphrase(context: Context): String
}

class AndroidKeystorePassphraseProvider : StorePassphraseProvider {
    @SuppressLint("ApplySharedPref")
    override fun getPassphrase(context: Context): String = synchronized(PASSPHRASE_LOCK) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(KEY_ENCRYPTED_PASSPHRASE, null)
        val iv = prefs.getString(KEY_IV, null)
        check((encrypted == null) == (iv == null)) {
            "The encrypted local-store passphrase metadata is incomplete."
        }
        if (encrypted != null && iv != null) {
            return@synchronized decrypt(
                ciphertext = Base64.decode(encrypted, Base64.NO_WRAP),
                iv = Base64.decode(iv, Base64.NO_WRAP),
            )
        }

        val passphrase = generatePassphrase()
        val encryption = encrypt(passphrase)
        val persisted = prefs.edit()
            .putString(KEY_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encryption.ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(encryption.iv, Base64.NO_WRAP))
            .commit()
        check(persisted) { "Could not persist the encrypted local-store passphrase." }
        passphrase
    }

    private fun generatePassphrase(): String {
        val bytes = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun encrypt(passphrase: String): EncryptionResult {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return EncryptionResult(
            ciphertext = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8)),
            iv = cipher.iv,
        )
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private data class EncryptionResult(
        val ciphertext: ByteArray,
        val iv: ByteArray,
    )

    private companion object {
        val PASSPHRASE_LOCK = Any()
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "grayin_local_store_key"
        const val PREFS_NAME = "grayin_secure_store"
        const val KEY_ENCRYPTED_PASSPHRASE = "encrypted_sqlcipher_passphrase"
        const val KEY_IV = "sqlcipher_passphrase_iv"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PASSPHRASE_BYTES = 32
    }
}
