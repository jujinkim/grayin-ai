package ai.grayin.core.transfer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object GrayinTransferEnvelopeFormat {
    const val MAGIC_ASCII = "GRAYINEX"
    const val ENVELOPE_VERSION = 1
    const val HEADER_BYTES = 64
    const val PBKDF2_ITERATIONS = 600_000
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val KEY_BITS = 256
    const val MAX_ENVELOPE_BYTES = HEADER_BYTES + TransferBounds.MAX_PLAINTEXT_BYTES + TAG_BYTES
}

fun interface TransferRandomSource {
    fun nextBytes(destination: ByteArray)
}

class GrayinTransferEnvelopeCodec(
    private val payloadCodec: GrayinTransferPayloadCodec = GrayinTransferPayloadCodec(),
    private val randomSource: TransferRandomSource = SecureTransferRandomSource(),
) {
    /** Validates only the bounded outer format; it does not authenticate or decode the payload. */
    fun preflight(envelope: ByteArray): TransferResult<Unit> {
        return when (val parsed = parseHeader(envelope)) {
            is TransferResult.Success -> TransferResult.Success(Unit)
            is TransferResult.Failure -> parsed
        }
    }

    /**
     * Encrypts a validated, detached seven-section transfer payload.
     *
     * The caller retains ownership of [password] and should clear it when the UI no longer needs it.
     */
    fun encrypt(payload: TransferPayload, password: CharArray): TransferResult<ByteArray> {
        if (!isAllowedPassword(password)) {
            return transferFailure(TransferFailureCode.PASSWORD_POLICY_FAILED)
        }
        val encoded = payloadCodec.encode(payload)
        if (encoded is TransferResult.Failure) return encoded
        val plaintext = (encoded as TransferResult.Success).value
        return try {
            val salt = ByteArray(GrayinTransferEnvelopeFormat.SALT_BYTES).also(randomSource::nextBytes)
            val nonce = ByteArray(GrayinTransferEnvelopeFormat.NONCE_BYTES).also(randomSource::nextBytes)
            val header = createHeader(
                plaintextLength = plaintext.size,
                salt = salt,
                nonce = nonce,
            )
            val key = deriveKey(password, salt)
            val ciphertext = Cipher.getInstance(CIPHER_TRANSFORMATION).run {
                init(
                    Cipher.ENCRYPT_MODE,
                    key,
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
                updateAAD(header)
                doFinal(plaintext)
            }
            check(ciphertext.size == plaintext.size + GrayinTransferEnvelopeFormat.TAG_BYTES)
            TransferResult.Success(header + ciphertext)
        } catch (_: GeneralSecurityException) {
            transferFailure(TransferFailureCode.CRYPTO_UNAVAILABLE)
        } catch (_: RuntimeException) {
            transferFailure(TransferFailureCode.CRYPTO_UNAVAILABLE)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Authenticates the complete envelope before decoding or returning any payload fields. */
    fun decrypt(envelope: ByteArray, password: CharArray): TransferResult<TransferPayload> {
        val parsedResult = parseHeader(envelope)
        if (parsedResult is TransferResult.Failure) return parsedResult
        if (!isAllowedPassword(password)) {
            return transferFailure(TransferFailureCode.PASSWORD_POLICY_FAILED)
        }
        val parsed = (parsedResult as TransferResult.Success).value
        var plaintext: ByteArray? = null
        return try {
            val key = deriveKey(password, parsed.salt)
            plaintext = Cipher.getInstance(CIPHER_TRANSFORMATION).run {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(GCM_TAG_BITS, parsed.nonce),
                )
                updateAAD(parsed.header)
                doFinal(
                    envelope,
                    GrayinTransferEnvelopeFormat.HEADER_BYTES,
                    parsed.ciphertextLength,
                )
            }
            if (plaintext.size != parsed.plaintextLength) {
                transferFailure(TransferFailureCode.INVALID_FORMAT)
            } else {
                payloadCodec.decode(plaintext)
            }
        } catch (_: AEADBadTagException) {
            transferFailure(TransferFailureCode.AUTHENTICATION_FAILED)
        } catch (_: BadPaddingException) {
            transferFailure(TransferFailureCode.AUTHENTICATION_FAILED)
        } catch (_: GeneralSecurityException) {
            transferFailure(TransferFailureCode.CRYPTO_UNAVAILABLE)
        } catch (_: RuntimeException) {
            transferFailure(TransferFailureCode.CRYPTO_UNAVAILABLE)
        } finally {
            plaintext?.fill(0)
        }
    }

    private fun parseHeader(envelope: ByteArray): TransferResult<ParsedEnvelopeHeader> {
        if (envelope.size > GrayinTransferEnvelopeFormat.MAX_ENVELOPE_BYTES) {
            return transferFailure(TransferFailureCode.TOO_LARGE)
        }
        if (envelope.size < GrayinTransferEnvelopeFormat.HEADER_BYTES + GrayinTransferEnvelopeFormat.TAG_BYTES) {
            return transferFailure(TransferFailureCode.INVALID_FORMAT)
        }
        return try {
            val header = envelope.copyOfRange(0, GrayinTransferEnvelopeFormat.HEADER_BYTES)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(GrayinTransferEnvelopeFormat.MAGIC_ASCII.length).also(buffer::get)
            if (!magic.contentEquals(MAGIC)) return transferFailure(TransferFailureCode.INVALID_FORMAT)

            val version = buffer.unsignedShort()
            if (version != GrayinTransferEnvelopeFormat.ENVELOPE_VERSION) {
                return transferFailure(TransferFailureCode.UNSUPPORTED_VERSION)
            }
            val headerLength = buffer.unsignedShort()
            val kdfId = buffer.unsignedByte()
            val cipherId = buffer.unsignedByte()
            val flags = buffer.unsignedShort()
            val iterations = buffer.int
            val saltLength = buffer.unsignedByte()
            val nonceLength = buffer.unsignedByte()
            val tagLength = buffer.unsignedByte()
            val firstReserved = buffer.unsignedByte()
            val plaintextLength = buffer.int
            val ciphertextLength = buffer.int
            val salt = ByteArray(GrayinTransferEnvelopeFormat.SALT_BYTES).also(buffer::get)
            val nonce = ByteArray(GrayinTransferEnvelopeFormat.NONCE_BYTES).also(buffer::get)
            val trailingReserved = buffer.int

            require(headerLength == GrayinTransferEnvelopeFormat.HEADER_BYTES)
            if (kdfId != KDF_ID || cipherId != CIPHER_ID) {
                return transferFailure(TransferFailureCode.UNSUPPORTED_VERSION)
            }
            require(flags == 0)
            require(iterations == GrayinTransferEnvelopeFormat.PBKDF2_ITERATIONS)
            require(saltLength == GrayinTransferEnvelopeFormat.SALT_BYTES)
            require(nonceLength == GrayinTransferEnvelopeFormat.NONCE_BYTES)
            require(tagLength == GrayinTransferEnvelopeFormat.TAG_BYTES)
            require(firstReserved == 0 && trailingReserved == 0)
            require(plaintextLength in 0..TransferBounds.MAX_PLAINTEXT_BYTES)
            require(ciphertextLength.toLong() == plaintextLength.toLong() + GrayinTransferEnvelopeFormat.TAG_BYTES)
            require(envelope.size.toLong() == GrayinTransferEnvelopeFormat.HEADER_BYTES.toLong() + ciphertextLength)
            TransferResult.Success(
                ParsedEnvelopeHeader(
                    header = header,
                    plaintextLength = plaintextLength,
                    ciphertextLength = ciphertextLength,
                    salt = salt,
                    nonce = nonce,
                ),
            )
        } catch (_: IllegalArgumentException) {
            transferFailure(TransferFailureCode.INVALID_FORMAT)
        } catch (_: RuntimeException) {
            transferFailure(TransferFailureCode.INVALID_FORMAT)
        }
    }

    private fun createHeader(
        plaintextLength: Int,
        salt: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        require(plaintextLength in 0..TransferBounds.MAX_PLAINTEXT_BYTES)
        require(salt.size == GrayinTransferEnvelopeFormat.SALT_BYTES)
        require(nonce.size == GrayinTransferEnvelopeFormat.NONCE_BYTES)
        return ByteBuffer.allocate(GrayinTransferEnvelopeFormat.HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .putShort(GrayinTransferEnvelopeFormat.ENVELOPE_VERSION.toShort())
            .putShort(GrayinTransferEnvelopeFormat.HEADER_BYTES.toShort())
            .put(KDF_ID.toByte())
            .put(CIPHER_ID.toByte())
            .putShort(0)
            .putInt(GrayinTransferEnvelopeFormat.PBKDF2_ITERATIONS)
            .put(GrayinTransferEnvelopeFormat.SALT_BYTES.toByte())
            .put(GrayinTransferEnvelopeFormat.NONCE_BYTES.toByte())
            .put(GrayinTransferEnvelopeFormat.TAG_BYTES.toByte())
            .put(0)
            .putInt(plaintextLength)
            .putInt(plaintextLength + GrayinTransferEnvelopeFormat.TAG_BYTES)
            .put(salt)
            .put(nonce)
            .putInt(0)
            .array()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val passwordCopy = password.copyOf()
        val spec = PBEKeySpec(
            passwordCopy,
            salt,
            GrayinTransferEnvelopeFormat.PBKDF2_ITERATIONS,
            GrayinTransferEnvelopeFormat.KEY_BITS,
        )
        var keyBytes: ByteArray? = null
        return try {
            keyBytes = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        } finally {
            keyBytes?.fill(0)
            spec.clearPassword()
            passwordCopy.fill('\u0000')
        }
    }

    private fun isAllowedPassword(password: CharArray): Boolean {
        return password.size in MIN_PASSWORD_CHARACTERS..MAX_PASSWORD_CHARACTERS
    }

    private fun ByteBuffer.unsignedByte(): Int = get().toInt() and 0xff

    private fun ByteBuffer.unsignedShort(): Int = short.toInt() and 0xffff

    private data class ParsedEnvelopeHeader(
        val header: ByteArray,
        val plaintextLength: Int,
        val ciphertextLength: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
    )

    private companion object {
        const val KDF_ID = 1
        const val CIPHER_ID = 1
        const val GCM_TAG_BITS = GrayinTransferEnvelopeFormat.TAG_BYTES * 8
        const val MIN_PASSWORD_CHARACTERS = 12
        const val MAX_PASSWORD_CHARACTERS = 128
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        val MAGIC = GrayinTransferEnvelopeFormat.MAGIC_ASCII.toByteArray(StandardCharsets.US_ASCII)
    }
}

private class SecureTransferRandomSource : TransferRandomSource {
    private val random = SecureRandom()

    override fun nextBytes(destination: ByteArray) {
        random.nextBytes(destination)
    }
}
