package ai.grayin.core.ai

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteRtLmModelImportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun officialV1PreambleAndBoundedFlatBufferHeaderAreAccepted() {
        val minimumHeader = modelFile(
            name = "minimum-header.litertlm",
            minorVersion = 99,
            patchVersion = 123,
            reservedPadding = 0x10203040,
        )
        val maximumHeader = modelFile(
            name = "maximum-header.litertlm",
            header = validHeader(LiteRtLmContainerPolicy.MAX_HEADER_BYTES),
        )

        LiteRtLmContainerPolicy.requireSupportedFile(minimumHeader)
        LiteRtLmContainerPolicy.requireSupportedFile(maximumHeader)

        assertTrue(LiteRtLmContainerPolicy.isSupportedFile(minimumHeader))
        assertTrue(LiteRtLmContainerPolicy.isSupportedFile(maximumHeader))
    }

    @Test
    fun readyPathSkipsReadableButUnverifiedCandidates() {
        val readableInvalid = modelFile(
            name = "readable-but-invalid.litertlm",
            magic = "NOTMODEL".toByteArray(Charsets.US_ASCII),
        )
        val compatible = modelFile("compatible.litertlm")

        assertEquals(
            compatible.canonicalFile,
            LiteRtLmReadyPathResolver.firstCompatibleFile(
                listOf(readableInvalid, readableInvalid, compatible),
            )?.canonicalFile,
        )
        assertEquals(null, LiteRtLmReadyPathResolver.firstCompatibleFile(listOf(readableInvalid)))
    }

    @Test
    fun invalidMagicMajorHeaderBoundsAndFlatBufferFailClosed() {
        val invalidFiles = listOf(
            modelFile(
                name = "wrong-magic.litertlm",
                magic = "NOTMODEL".toByteArray(Charsets.US_ASCII),
            ),
            modelFile(name = "wrong-major.litertlm", majorVersion = 2),
            modelFile(
                name = "oversized-header.litertlm",
                header = validHeader(LiteRtLmContainerPolicy.MAX_HEADER_BYTES + 1),
            ),
            modelFile(
                name = "empty-declared-header.litertlm",
                headerEndOffset = LiteRtLmContainerPolicy.PREAMBLE_BYTES.toLong(),
            ),
            modelFile(
                name = "malformed-flatbuffer.litertlm",
                header = ByteArray(12),
            ),
        )

        invalidFiles.forEach { file ->
            assertFalse(file.name, LiteRtLmContainerPolicy.isSupportedFile(file))
            assertThrows(IllegalArgumentException::class.java) {
                LiteRtLmContainerPolicy.requireSupportedFile(file)
            }
        }
    }

    @Test
    fun sourceAndStagingSizesAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            LiteRtLmContainerPolicy.requireSupportedSourceSize(LiteRtLmContainerPolicy.MIN_MODEL_BYTES - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiteRtLmContainerPolicy.requireSupportedSourceSize(LiteRtLmContainerPolicy.MAX_MODEL_BYTES + 1)
        }
        assertThrows(IOException::class.java) {
            LiteRtLmContainerPolicy.requireStagingSpace(
                sizeBytes = LiteRtLmContainerPolicy.MIN_MODEL_BYTES,
                usableSpaceBytes = LiteRtLmContainerPolicy.MIN_MODEL_BYTES +
                    LiteRtLmContainerPolicy.IMPORT_SPACE_RESERVE_BYTES - 1,
            )
        }
    }

    @Test
    fun validatedStagingAtomicallyReplacesExistingVerifiedModel() {
        val destination = modelFile("installed.litertlm", marker = OLD_MARKER)
        val source = modelFile("selected.litertlm", marker = NEW_MARKER)
        val staging = File(destination.parentFile, "installed.litertlm.importing")

        val copied = LocalModelImportTransaction().import(
            source = FileInputStream(source),
            expectedBytes = source.length(),
            usableSpaceBytes = enoughSpaceFor(source.length()),
            stagingFile = staging,
            destinationFile = destination,
        )

        assertEquals(source.length(), copied)
        assertEquals(NEW_MARKER, markerOf(destination))
        assertTrue(LiteRtLmContainerPolicy.isSupportedFile(destination))
        assertFalse(staging.exists())
    }

    @Test
    fun invalidImportDeletesStagingAndPreservesExistingVerifiedModel() {
        val destination = modelFile("installed-invalid.litertlm", marker = OLD_MARKER)
        val invalidSource = modelFile(
            name = "selected-invalid.litertlm",
            magic = "NOTMODEL".toByteArray(Charsets.US_ASCII),
            marker = NEW_MARKER,
        )
        val staging = File(destination.parentFile, "installed-invalid.litertlm.importing")

        assertThrows(IllegalArgumentException::class.java) {
            LocalModelImportTransaction().import(
                source = FileInputStream(invalidSource),
                expectedBytes = invalidSource.length(),
                usableSpaceBytes = enoughSpaceFor(invalidSource.length()),
                stagingFile = staging,
                destinationFile = destination,
            )
        }

        assertEquals(OLD_MARKER, markerOf(destination))
        assertTrue(LiteRtLmContainerPolicy.isSupportedFile(destination))
        assertFalse(staging.exists())
    }

    @Test
    fun cancellationDeletesStagingAndPreservesExistingVerifiedModel() {
        val destination = modelFile("installed-canceled.litertlm", marker = OLD_MARKER)
        val source = modelFile(
            name = "selected-canceled.litertlm",
            sizeBytes = LiteRtLmContainerPolicy.MIN_MODEL_BYTES * 2,
            marker = NEW_MARKER,
        )
        val staging = File(destination.parentFile, "installed-canceled.litertlm.importing")
        var checks = 0

        assertThrows(CancellationException::class.java) {
            LocalModelImportTransaction().import(
                source = FileInputStream(source),
                expectedBytes = source.length(),
                usableSpaceBytes = enoughSpaceFor(source.length()),
                stagingFile = staging,
                destinationFile = destination,
                cancellationCheck = {
                    checks += 1
                    if (checks == 3) throw CancellationException("test cancellation")
                },
            )
        }

        assertEquals(OLD_MARKER, markerOf(destination))
        assertTrue(LiteRtLmContainerPolicy.isSupportedFile(destination))
        assertFalse(staging.exists())
    }

    @Test
    fun unavailableAtomicMoveNeverFallsBackToNonAtomicReplacement() {
        val destination = modelFile("installed-no-atomic.litertlm", marker = OLD_MARKER)
        val source = modelFile("selected-no-atomic.litertlm", marker = NEW_MARKER)
        val staging = File(destination.parentFile, "installed-no-atomic.litertlm.importing")
        val transaction = LocalModelImportTransaction { from, to ->
            throw AtomicMoveNotSupportedException(from.path, to.path, "test filesystem")
        }

        assertThrows(AtomicMoveNotSupportedException::class.java) {
            transaction.import(
                source = FileInputStream(source),
                expectedBytes = source.length(),
                usableSpaceBytes = enoughSpaceFor(source.length()),
                stagingFile = staging,
                destinationFile = destination,
            )
        }

        assertEquals(OLD_MARKER, markerOf(destination))
        assertTrue(LiteRtLmContainerPolicy.isSupportedFile(destination))
        assertFalse(staging.exists())
    }

    @Test
    fun declaredSizeMismatchAndInsufficientSpacePreserveExistingModel() {
        val destination = modelFile("installed-bounds.litertlm", marker = OLD_MARKER)
        val sourceBytes = ByteArray(LiteRtLmContainerPolicy.MIN_MODEL_BYTES.toInt() + 1)
        val sizeMismatchStaging = File(destination.parentFile, "size-mismatch.importing")

        assertThrows(IllegalArgumentException::class.java) {
            LocalModelImportTransaction().import(
                source = ByteArrayInputStream(sourceBytes),
                expectedBytes = LiteRtLmContainerPolicy.MIN_MODEL_BYTES,
                usableSpaceBytes = enoughSpaceFor(LiteRtLmContainerPolicy.MIN_MODEL_BYTES),
                stagingFile = sizeMismatchStaging,
                destinationFile = destination,
            )
        }

        val noSpaceStaging = File(destination.parentFile, "no-space.importing").apply {
            writeText("stale")
        }
        assertThrows(IOException::class.java) {
            LocalModelImportTransaction().import(
                source = FileInputStream(modelFile("selected-no-space.litertlm")),
                expectedBytes = LiteRtLmContainerPolicy.MIN_MODEL_BYTES,
                usableSpaceBytes = LiteRtLmContainerPolicy.MIN_MODEL_BYTES +
                    LiteRtLmContainerPolicy.IMPORT_SPACE_RESERVE_BYTES - 1,
                stagingFile = noSpaceStaging,
                destinationFile = destination,
            )
        }

        assertEquals(OLD_MARKER, markerOf(destination))
        assertTrue(LiteRtLmContainerPolicy.isSupportedFile(destination))
        assertFalse(sizeMismatchStaging.exists())
        assertFalse(noSpaceStaging.exists())
    }

    private fun modelFile(
        name: String,
        sizeBytes: Long = LiteRtLmContainerPolicy.MIN_MODEL_BYTES,
        magic: ByteArray = "LITERTLM".toByteArray(Charsets.US_ASCII),
        majorVersion: Int = LiteRtLmContainerPolicy.REQUIRED_MAJOR_VERSION,
        minorVersion: Int = 5,
        patchVersion: Int = 0,
        reservedPadding: Int = 0,
        header: ByteArray = validHeader(),
        headerEndOffset: Long = LiteRtLmContainerPolicy.PREAMBLE_BYTES.toLong() + header.size,
        marker: Int = 0,
    ): File {
        require(magic.size == 8)
        require(sizeBytes >= LiteRtLmContainerPolicy.PREAMBLE_BYTES + header.size)
        val file = File(temporaryFolder.root, name)
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(sizeBytes)
            val preamble = ByteBuffer.allocate(LiteRtLmContainerPolicy.PREAMBLE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(magic)
                .putInt(majorVersion)
                .putInt(minorVersion)
                .putInt(patchVersion)
                .putInt(reservedPadding)
                .putLong(headerEndOffset)
                .array()
            output.seek(0)
            output.write(preamble)
            output.write(header)
            output.seek(sizeBytes - 1)
            output.write(marker)
        }
        return file
    }

    private fun validHeader(sizeBytes: Int = 12): ByteArray {
        require(sizeBytes >= 12)
        return ByteBuffer.allocate(sizeBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(8) // Root table begins at offset 8.
            .putShort(4) // Minimal vtable size.
            .putShort(4) // Minimal table object size.
            .putInt(4) // Table points four bytes backward to its vtable.
            .array()
    }

    private fun markerOf(file: File): Int {
        return RandomAccessFile(file, "r").use { input ->
            input.seek(file.length() - 1)
            input.read()
        }
    }

    private fun enoughSpaceFor(sourceBytes: Long): Long {
        return sourceBytes + LiteRtLmContainerPolicy.IMPORT_SPACE_RESERVE_BYTES
    }

    private companion object {
        const val OLD_MARKER = 0x11
        const val NEW_MARKER = 0x22
    }
}
