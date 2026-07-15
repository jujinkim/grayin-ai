package ai.grayin.core.transfer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayinTransferEnvelopeCodecTest {
    private val password = "correct horse battery".toCharArray()

    @Test
    fun `v1 envelope has the exact fixed header and authenticated round trip`() {
        val codec = deterministicCodec()
        val original = TransferTestFixtures.payload()

        val envelope = success(codec.encrypt(original, password))
        success(codec.preflight(envelope))
        val header = ByteBuffer.wrap(envelope, 0, GrayinTransferEnvelopeFormat.HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(8).also(header::get)

        assertArrayEquals("GRAYINEX".toByteArray(StandardCharsets.US_ASCII), magic)
        assertEquals(1, header.short.toInt() and 0xffff)
        assertEquals(64, header.short.toInt() and 0xffff)
        assertEquals(1, header.get().toInt() and 0xff)
        assertEquals(1, header.get().toInt() and 0xff)
        assertEquals(0, header.short.toInt() and 0xffff)
        assertEquals(600_000, header.int)
        assertEquals(16, header.get().toInt() and 0xff)
        assertEquals(12, header.get().toInt() and 0xff)
        assertEquals(16, header.get().toInt() and 0xff)
        assertEquals(0, header.get().toInt() and 0xff)
        val plaintextLength = header.int
        val ciphertextLength = header.int
        assertTrue(plaintextLength > 0)
        assertEquals(plaintextLength + 16, ciphertextLength)
        assertEquals(64 + ciphertextLength, envelope.size)
        assertArrayEquals(ByteArray(16) { index -> index.toByte() }, ByteArray(16).also(header::get))
        assertArrayEquals(ByteArray(12) { index -> (index + 16).toByte() }, ByteArray(12).also(header::get))
        assertEquals(0, header.int)

        val decoded = success(codec.decrypt(envelope, password))
        assertEquals(
            original.copy(snapshot = TransferSnapshotValidator.detached(original.snapshot)),
            decoded,
        )
    }

    @Test
    fun `wrong password and ciphertext tamper have the same authentication failure`() {
        val codec = deterministicCodec()
        val envelope = success(codec.encrypt(TransferTestFixtures.payload(), password))
        val tampered = envelope.copyOf().also { bytes ->
            bytes[GrayinTransferEnvelopeFormat.HEADER_BYTES + 5] =
                (bytes[GrayinTransferEnvelopeFormat.HEADER_BYTES + 5].toInt() xor 1).toByte()
        }

        assertFailure(
            TransferFailureCode.AUTHENTICATION_FAILED,
            codec.decrypt(envelope, "incorrect horse pass".toCharArray()),
        )
        assertFailure(TransferFailureCode.AUTHENTICATION_FAILED, codec.decrypt(tampered, password))
    }

    @Test
    fun `salt and header AAD tamper cannot authenticate`() {
        val codec = deterministicCodec()
        val envelope = success(codec.encrypt(TransferTestFixtures.payload(), password))
        val saltTamper = envelope.copyOf().also { bytes -> bytes[32] = (bytes[32].toInt() xor 1).toByte() }

        assertFailure(TransferFailureCode.AUTHENTICATION_FAILED, codec.decrypt(saltTamper, password))
    }

    @Test
    fun `repeated encryption is randomized and never exposes plaintext sentinel`() {
        val sentinel = "GRAYIN-PLAINTEXT-SENTINEL-4f5c8a2e"
        val original = TransferTestFixtures.payload().let { payload ->
            payload.copy(
                snapshot = payload.snapshot.copy(
                    derivedMemoryEvents = payload.snapshot.derivedMemoryEvents.mapIndexed { index, event ->
                        if (index == 0) event.copy(summary = sentinel) else event
                    },
                ),
            )
        }
        val codec = deterministicCodec()

        val first = success(codec.encrypt(original, password))
        val second = success(codec.encrypt(original, password))
        val sentinelBytes = sentinel.toByteArray(StandardCharsets.UTF_8)

        assertFalse(first.contentEquals(second))
        assertFalse(first.containsSubsequence(sentinelBytes))
        assertFalse(second.containsSubsequence(sentinelBytes))
    }

    @Test
    fun `unsupported version and algorithms fail before decryption`() {
        val codec = deterministicCodec()
        val envelope = success(codec.encrypt(TransferTestFixtures.payload(), password))
        val versionTwo = envelope.copyOf().also { bytes -> bytes[9] = 2 }
        val unknownKdf = envelope.copyOf().also { bytes -> bytes[12] = 2 }
        val unknownCipher = envelope.copyOf().also { bytes -> bytes[13] = 2 }

        assertFailure(TransferFailureCode.UNSUPPORTED_VERSION, codec.decrypt(versionTwo, password))
        assertFailure(TransferFailureCode.UNSUPPORTED_VERSION, codec.decrypt(unknownKdf, password))
        assertFailure(TransferFailureCode.UNSUPPORTED_VERSION, codec.decrypt(unknownCipher, password))
    }

    @Test
    fun `malformed reserved length truncated and trailing envelopes fail closed`() {
        val codec = deterministicCodec()
        val envelope = success(codec.encrypt(TransferTestFixtures.payload(), password))
        val reserved = envelope.copyOf().also { bytes -> bytes[23] = 1 }
        val lengthMismatch = envelope.copyOf().also { bytes -> bytes[27] = (bytes[27] + 1).toByte() }
        val truncated = envelope.copyOf(envelope.size - 1)
        val trailing = envelope + byteArrayOf(0)

        listOf(reserved, lengthMismatch, truncated, trailing).forEach { malformed ->
            assertFailure(TransferFailureCode.INVALID_FORMAT, codec.preflight(malformed))
            assertFailure(TransferFailureCode.INVALID_FORMAT, codec.decrypt(malformed, password))
        }
    }

    @Test
    fun `invalid magic and short envelopes fail closed`() {
        val codec = deterministicCodec()
        val envelope = success(codec.encrypt(TransferTestFixtures.payload(), password))
        val badMagic = envelope.copyOf().also { bytes -> bytes[0] = 'X'.code.toByte() }

        assertFailure(TransferFailureCode.INVALID_FORMAT, codec.decrypt(badMagic, password))
        assertFailure(TransferFailureCode.INVALID_FORMAT, codec.decrypt(ByteArray(79), password))
    }

    @Test
    fun `password policy is stable and preserves caller owned characters`() {
        val codec = deterministicCodec()
        val callerPassword = password.copyOf()

        assertFailure(
            TransferFailureCode.PASSWORD_POLICY_FAILED,
            codec.encrypt(TransferTestFixtures.payload(), "too short".toCharArray()),
        )
        success(codec.encrypt(TransferTestFixtures.payload(), callerPassword))
        assertArrayEquals(password, callerPassword)
    }

    @Test
    fun `Unicode passphrases round trip without normalization`() {
        val codec = deterministicCodec()
        val unicodePassword = "기억-パスワード-🔐-2026".toCharArray()
        val envelope = success(codec.encrypt(TransferTestFixtures.payload(), unicodePassword))

        assertEquals(
            TransferTestFixtures.payload().copy(
                snapshot = TransferSnapshotValidator.detached(TransferTestFixtures.payload().snapshot),
            ),
            success(codec.decrypt(envelope, unicodePassword)),
        )
    }

    @Test
    fun `all-whitespace passphrase is used exactly without trimming`() {
        val codec = deterministicCodec()
        val whitespacePassword = "            ".toCharArray()
        val envelope = success(codec.encrypt(TransferTestFixtures.payload(), whitespacePassword))

        assertEquals(
            TransferSnapshotValidator.detached(TransferTestFixtures.payload().snapshot),
            success(codec.decrypt(envelope, whitespacePassword)).snapshot,
        )
    }

    @Test
    fun `public v1 maximum includes only header plaintext and tag`() {
        assertEquals(33_554_512, GrayinTransferEnvelopeFormat.MAX_ENVELOPE_BYTES)
        assertEquals(32 * 1024 * 1024, TransferBounds.MAX_PLAINTEXT_BYTES)
    }

    private fun deterministicCodec(): GrayinTransferEnvelopeCodec {
        var nextByte = 0
        return GrayinTransferEnvelopeCodec(
            randomSource = TransferRandomSource { destination ->
                destination.indices.forEach { index -> destination[index] = nextByte++.toByte() }
            },
        )
    }

    private fun <T> success(result: TransferResult<T>): T {
        assertTrue(result is TransferResult.Success)
        return (result as TransferResult.Success).value
    }

    private fun assertFailure(expected: TransferFailureCode, result: TransferResult<*>) {
        assertTrue(result is TransferResult.Failure)
        val actual = (result as TransferResult.Failure).failure.code
        assertEquals(expected, actual)
        assertNull(result.getOrNull())
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
        if (candidate.isEmpty()) return true
        if (candidate.size > size) return false
        return (0..size - candidate.size).any { start ->
            candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
        }
    }
}
