package ai.grayin.core.ai

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object LiteRtLmContainerPolicy {
    const val PREAMBLE_BYTES = 32
    const val MAX_HEADER_BYTES = 16 * 1024
    const val REQUIRED_MAJOR_VERSION = 1
    const val MIN_MODEL_BYTES = 1024L * 1024L
    const val MAX_MODEL_BYTES = 8L * 1024L * 1024L * 1024L
    const val IMPORT_SPACE_RESERVE_BYTES = 16L * 1024L * 1024L

    private val magic = "LITERTLM".toByteArray(Charsets.US_ASCII)

    fun isSupportedFile(file: File): Boolean {
        return try {
            requireSupportedFile(file)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun requireSupportedFile(file: File) {
        require(!Files.isSymbolicLink(file.toPath())) { "Model file must not be a symbolic link." }
        require(file.isFile && file.canRead()) { "Model file is not a readable regular file." }
        val fileSize = file.length()
        requireSupportedSourceSize(fileSize)

        FileInputStream(file).use { source ->
            val preamble = ByteArray(PREAMBLE_BYTES)
            source.readExactly(preamble)
            require(preamble.copyOfRange(0, magic.size).contentEquals(magic)) {
                "Model file has an invalid LiteRT-LM magic value."
            }

            val fields = ByteBuffer.wrap(preamble).order(ByteOrder.LITTLE_ENDIAN)
            fields.position(magic.size)
            val majorVersion = fields.int
            fields.int // Minor version is backward-compatible within major version 1.
            fields.int // Patch version is backward-compatible within major version 1.
            fields.int // Reserved padding is ignored by the LiteRT-LM 0.13.1 reader.
            val headerEndOffset = fields.long
            require(majorVersion == REQUIRED_MAJOR_VERSION) {
                "Model file uses an unsupported LiteRT-LM major version."
            }
            require(headerEndOffset > PREAMBLE_BYTES.toLong()) {
                "Model file has an invalid LiteRT-LM header end offset."
            }
            val headerBytes = headerEndOffset - PREAMBLE_BYTES
            require(headerBytes in MIN_FLATBUFFER_HEADER_BYTES.toLong()..MAX_HEADER_BYTES.toLong()) {
                "Model file has an unsupported LiteRT-LM header size."
            }
            require(headerEndOffset <= fileSize) { "Model file has a truncated LiteRT-LM header." }

            val header = ByteArray(headerBytes.toInt())
            source.readExactly(header)
            requirePlausibleFlatBufferRoot(header)
        }
    }

    fun requireSupportedSourceSize(sizeBytes: Long) {
        require(sizeBytes in MIN_MODEL_BYTES..MAX_MODEL_BYTES) {
            "Model file size is outside the supported import range."
        }
    }

    @Throws(IOException::class)
    fun requireStagingSpace(sizeBytes: Long, usableSpaceBytes: Long) {
        requireSupportedSourceSize(sizeBytes)
        if (
            usableSpaceBytes < IMPORT_SPACE_RESERVE_BYTES ||
            sizeBytes > usableSpaceBytes - IMPORT_SPACE_RESERVE_BYTES
        ) {
            throw IOException("Insufficient app-private space for atomic model staging.")
        }
    }

    private fun requirePlausibleFlatBufferRoot(header: ByteArray) {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val rootOffset = Integer.toUnsignedLong(buffer.getInt(0))
        require(rootOffset >= Int.SIZE_BYTES && rootOffset <= header.size.toLong() - Int.SIZE_BYTES) {
            "Model file has an invalid LiteRT-LM FlatBuffer root offset."
        }
        val tableOffset = rootOffset.toInt()
        val vtableDistance = buffer.getInt(tableOffset)
        require(vtableDistance > 0 && vtableDistance <= tableOffset) {
            "Model file has an invalid LiteRT-LM FlatBuffer table."
        }
        val vtableOffset = tableOffset - vtableDistance
        require(vtableOffset <= header.size - MIN_VTABLE_BYTES) {
            "Model file has a truncated LiteRT-LM FlatBuffer vtable."
        }
        val vtableBytes = buffer.getShort(vtableOffset).toInt() and 0xffff
        val tableBytes = buffer.getShort(vtableOffset + Short.SIZE_BYTES).toInt() and 0xffff
        require(vtableBytes >= MIN_VTABLE_BYTES && vtableBytes % Short.SIZE_BYTES == 0) {
            "Model file has an invalid LiteRT-LM FlatBuffer vtable size."
        }
        require(tableBytes >= Int.SIZE_BYTES) {
            "Model file has an invalid LiteRT-LM FlatBuffer table size."
        }
        require(vtableOffset.toLong() + vtableBytes <= header.size.toLong()) {
            "Model file has a truncated LiteRT-LM FlatBuffer vtable."
        }
        require(tableOffset.toLong() + tableBytes <= header.size.toLong()) {
            "Model file has a truncated LiteRT-LM FlatBuffer table."
        }
    }

    private fun InputStream.readExactly(destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val read = read(destination, offset, destination.size - offset)
            if (read < 0) throw IOException("Model file ended before the LiteRT-LM header was complete.")
            if (read == 0) continue
            offset += read
        }
    }

    private const val MIN_FLATBUFFER_HEADER_BYTES = 12
    private const val MIN_VTABLE_BYTES = 4
}

internal object LiteRtLmReadyPathResolver {
    fun firstCompatibleFile(candidates: List<File>): File? {
        return candidates
            .distinctBy(File::getAbsolutePath)
            .firstOrNull(LiteRtLmContainerPolicy::isSupportedFile)
    }
}

internal class LocalModelImportTransaction(
    private val atomicPublisher: (stagingFile: File, destinationFile: File) -> Unit =
        LocalModelAtomicPublisher::publish,
) {
    fun import(
        source: InputStream,
        expectedBytes: Long,
        usableSpaceBytes: Long,
        stagingFile: File,
        destinationFile: File,
        cancellationCheck: () -> Unit = {},
    ): Long {
        try {
            return source.use { input ->
                LiteRtLmContainerPolicy.requireStagingSpace(expectedBytes, usableSpaceBytes)
                requireSameParent(stagingFile, destinationFile)
                if (stagingFile.exists() && !stagingFile.delete()) {
                    throw IOException("Stale model staging file cannot be removed.")
                }

                cancellationCheck()
                var copiedBytes = 0L
                FileOutputStream(stagingFile, false).use { target ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        cancellationCheck()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (copiedBytes > expectedBytes - read) {
                            throw IllegalArgumentException("Model source exceeded its declared size.")
                        }
                        target.write(buffer, 0, read)
                        copiedBytes += read
                    }
                    target.fd.sync()
                }
                require(copiedBytes == expectedBytes && stagingFile.length() == expectedBytes) {
                    "Model source size changed during import."
                }
                LiteRtLmContainerPolicy.requireSupportedFile(stagingFile)
                cancellationCheck()
                atomicPublisher(stagingFile, destinationFile)
                copiedBytes
            }
        } catch (error: Throwable) {
            stagingFile.delete()
            throw error
        }
    }

    private fun requireSameParent(stagingFile: File, destinationFile: File) {
        val stagingParent = requireNotNull(stagingFile.parentFile) { "Model staging directory is unavailable." }
        val destinationParent = requireNotNull(destinationFile.parentFile) { "Model directory is unavailable." }
        require(stagingParent.canonicalFile == destinationParent.canonicalFile) {
            "Model staging and destination files must share one filesystem directory."
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 1024 * 1024
    }
}

internal object LocalModelAtomicPublisher {
    @Throws(IOException::class)
    fun publish(stagingFile: File, destinationFile: File) {
        Files.move(
            stagingFile.toPath(),
            destinationFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
