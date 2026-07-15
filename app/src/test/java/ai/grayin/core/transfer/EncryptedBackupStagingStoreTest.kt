package ai.grayin.core.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedBackupStagingStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stagesReadsAndCopiesCiphertextWithOpaqueTokenAndAtomicFile() = runBlocking {
        val root = temporaryFolder.newFolder("ciphertext-stage")
        val area = area(root, maxBytes = 128)
        val ciphertext = "authenticated-ciphertext".toByteArray()

        val staged = area.stage(ByteArrayInputStream(ciphertext), ciphertext.size.toLong())

        assertTrue(staged is BackupFileResult.Success)
        val stage = (staged as BackupFileResult.Success).value
        assertTrue(stage.token.matches(Regex("[a-f0-9]{64}")))
        assertEquals(ciphertext.size.toLong(), stage.sizeBytes)
        assertEquals(listOf("${stage.token}.ciphertext"), root.listFiles().orEmpty().map(File::getName))
        assertArrayEquals(ciphertext, (area.read(stage.token) as BackupFileResult.Success).value)

        var syncCalled = false
        val copied = ByteArrayOutputStream()
        assertEquals(
            BackupFileResult.Success(ciphertext.size.toLong()),
            area.copyTo(stage.token, copied) { syncCalled = true },
        )
        assertTrue(syncCalled)
        assertArrayEquals(ciphertext, copied.toByteArray())
        assertTrue(area.discard(stage.token))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsKnownOversizeBeforeReadingAndStreamedOversizeWithoutResidue() = runBlocking {
        val root = temporaryFolder.newFolder("bounded-stage")
        val area = area(root, maxBytes = 4)
        val mustNotRead = object : InputStream() {
            override fun read(): Int = error("oversized known input must not be opened")
        }

        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_TOO_LARGE),
            area.stage(mustNotRead, knownSizeBytes = 5),
        )
        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_TOO_LARGE),
            area.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))),
        )
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsEmptyMismatchedAndFailedInputWithoutResidue() = runBlocking {
        val root = temporaryFolder.newFolder("failed-stage")
        val area = area(root, maxBytes = 64)

        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.EMPTY_CIPHERTEXT),
            area.stage(ByteArrayInputStream(byteArrayOf()), knownSizeBytes = 0),
        )
        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH),
            area.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)), knownSizeBytes = 4),
        )
        val broken = object : InputStream() {
            private var emitted = false

            override fun read(): Int {
                if (!emitted) {
                    emitted = true
                    return 1
                }
                throw IOException("synthetic failure")
            }
        }
        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE),
            area.stage(broken),
        )
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun invalidTokenCannotEscapeStageDirectory() = runBlocking {
        val root = temporaryFolder.newFolder("token-stage")
        val area = area(root, maxBytes = 64)

        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.INVALID_TOKEN),
            area.read("../outside"),
        )
        assertFalse(area.discard("../outside"))
        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.STAGE_NOT_FOUND),
            area.read("f".repeat(64)),
        )
    }

    @Test
    fun publicStorePreflightsBeforeCreatingCiphertextStage() = runBlocking {
        val root = temporaryFolder.newFolder("preflight-stage")
        val store = EncryptedBackupStagingStore(
            rootDirectory = root,
            maxCiphertextBytes = GrayinTransferEnvelopeFormat.MAX_ENVELOPE_BYTES.toLong(),
            clock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC),
            tokenGenerator = OpaqueBackupTokenGenerator { "d".repeat(64) },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.INVALID_CIPHERTEXT_FORMAT),
            store.stageCiphertext("not-an-envelope".toByteArray()),
        )
        val unsupported = ByteArray(
            GrayinTransferEnvelopeFormat.HEADER_BYTES + GrayinTransferEnvelopeFormat.TAG_BYTES,
        ).also { bytes ->
            GrayinTransferEnvelopeFormat.MAGIC_ASCII.toByteArray().copyInto(bytes)
            bytes[8] = 0
            bytes[9] = 2
        }
        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.UNSUPPORTED_CIPHERTEXT_VERSION),
            store.stageCiphertext(unsupported),
        )
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun boundedMemoryReadRejectsDeclaredAndStreamedOversize() = runBlocking {
        val root = temporaryFolder.newFolder("bounded-read")
        val area = area(root, maxBytes = 4)
        val mustNotRead = object : InputStream() {
            override fun read(): Int = error("declared oversized input must not be read")
        }

        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_TOO_LARGE),
            area.readBounded(mustNotRead, knownSizeBytes = 5),
        )
        assertEquals(
            BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_TOO_LARGE),
            area.readBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))),
        )
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cleanupDeletesOnlyRecognizedStaleCiphertextAndPartFiles() {
        val root = temporaryFolder.newFolder("cleanup-stage")
        val area = area(root, maxBytes = 64)
        val oldCiphertext = File(root, "a".repeat(64) + ".ciphertext").apply { writeBytes(byteArrayOf(1)) }
        val oldPart = File(root, "b".repeat(64) + ".part").apply { writeBytes(byteArrayOf(1)) }
        val freshCiphertext = File(root, "c".repeat(64) + ".ciphertext").apply { writeBytes(byteArrayOf(1)) }
        val unrelated = File(root, "keep.txt").apply { writeText("keep") }
        val oldMillis = Instant.parse("2026-07-15T09:00:00Z").toEpochMilli()
        val freshMillis = Instant.parse("2026-07-15T11:30:00Z").toEpochMilli()
        oldCiphertext.setLastModified(oldMillis)
        oldPart.setLastModified(oldMillis)
        freshCiphertext.setLastModified(freshMillis)
        unrelated.setLastModified(oldMillis)

        assertEquals(2, area.cleanupStale(Duration.ofHours(1)))
        assertFalse(oldCiphertext.exists())
        assertFalse(oldPart.exists())
        assertTrue(freshCiphertext.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun secureGeneratorProducesIndependentOpaqueTokens() {
        val generator = SecureOpaqueBackupTokenGenerator()

        val first = generator.nextToken()
        val second = generator.nextToken()

        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertTrue(second.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(first, second)
    }

    @Test
    fun sourcePinsNoBackupStorageFsyncAtomicMoveAndClosedBound() {
        val source = File("src/main/java/ai/grayin/core/transfer/EncryptedBackupStagingStore.kt").readText()

        assertTrue(source.contains("noBackupFilesDir"))
        assertTrue(source.contains("target.fd.sync()"))
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(source.contains("envelopeCodec.preflight(ciphertext)"))
        assertEquals(
            GrayinTransferEnvelopeFormat.MAX_ENVELOPE_BYTES.toLong(),
            EncryptedBackupStagingStore.DEFAULT_MAX_CIPHERTEXT_BYTES,
        )
        assertFalse(source.contains("cacheDir"))
        assertFalse(source.contains("filesDir"))
    }

    private fun area(root: File, maxBytes: Long): CiphertextStagingArea {
        var counter = 0L
        return CiphertextStagingArea(
            rootDirectory = root,
            maxCiphertextBytes = maxBytes,
            clock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC),
            tokenGenerator = OpaqueBackupTokenGenerator {
                counter += 1
                counter.toString(16).padStart(64, '0')
            },
        )
    }
}
