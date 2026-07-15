package ai.grayin.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

fun interface SourceIdentityHasher {
    fun hmac(namespace: String, value: String): String
}

class AndroidKeystoreSourceIdentityHasher : SourceIdentityHasher {
    override fun hmac(namespace: String, value: String): String {
        require(namespace.matches(SAFE_NAMESPACE)) { "Source identity namespace is invalid." }
        val namespaceBytes = namespace.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        require(valueBytes.size <= MAX_VALUE_BYTES) { "Source identity input exceeds the fixed limit." }
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(getOrCreateKey())
        updateLengthPrefixed(mac, VERSION_BYTES)
        updateLengthPrefixed(mac, namespaceBytes)
        updateLengthPrefixed(mac, valueBytes)
        return mac.doFinal().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return@synchronized existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKey()
    }

    private fun updateLengthPrefixed(mac: Mac, bytes: ByteArray) {
        mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        mac.update(bytes)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "grayin_source_identity_hmac_v1"
        val VERSION_BYTES = "grayin-source-identity-v1".toByteArray(Charsets.UTF_8)
        val SAFE_NAMESPACE = Regex("[a-z0-9_-]{1,64}")
        const val MAX_VALUE_BYTES = 16 * 1024
        val KEY_LOCK = Any()
    }
}
