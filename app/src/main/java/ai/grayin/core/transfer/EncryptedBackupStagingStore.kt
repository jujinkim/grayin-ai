package ai.grayin.core.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class BackupFileFailureCode {
    INVALID_TOKEN,
    INVALID_DOCUMENT_URI,
    EMPTY_CIPHERTEXT,
    CIPHERTEXT_TOO_LARGE,
    CIPHERTEXT_SIZE_MISMATCH,
    INVALID_CIPHERTEXT_FORMAT,
    UNSUPPORTED_CIPHERTEXT_VERSION,
    STAGE_NOT_FOUND,
    DOCUMENT_UNAVAILABLE,
    IO_FAILURE,
}

sealed interface BackupFileResult<out T> {
    data class Success<T>(val value: T) : BackupFileResult<T>

    data class Failed(val code: BackupFileFailureCode) : BackupFileResult<Nothing>
}

data class StagedBackupCiphertext(
    val token: String,
    val sizeBytes: Long,
)

/**
 * Owns one-shot encrypted backup ciphertext under [Context.getNoBackupFilesDir].
 *
 * This class has no plaintext or password API. Callers must authenticate/decrypt only after
 * [readCiphertext], and must call [discard] after a terminal export or import outcome.
 */
class EncryptedBackupStagingStore private constructor(
    private val contentResolver: ContentResolver?,
    private val stagingArea: CiphertextStagingArea,
    private val envelopeCodec: GrayinTransferEnvelopeCodec,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(
        context: Context,
        maxCiphertextBytes: Long = DEFAULT_MAX_CIPHERTEXT_BYTES,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        contentResolver = context.applicationContext.contentResolver,
        stagingArea = CiphertextStagingArea(
            rootDirectory = File(context.applicationContext.noBackupFilesDir, STAGING_DIRECTORY),
            maxCiphertextBytes = maxCiphertextBytes,
        ),
        envelopeCodec = GrayinTransferEnvelopeCodec(),
        ioDispatcher = ioDispatcher,
    )

    internal constructor(
        rootDirectory: File,
        maxCiphertextBytes: Long,
        clock: Clock,
        tokenGenerator: OpaqueBackupTokenGenerator,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        contentResolver = null,
        stagingArea = CiphertextStagingArea(
            rootDirectory = rootDirectory,
            maxCiphertextBytes = maxCiphertextBytes,
            clock = clock,
            tokenGenerator = tokenGenerator,
        ),
        envelopeCodec = GrayinTransferEnvelopeCodec(),
        ioDispatcher = ioDispatcher,
    )

    suspend fun stageCiphertext(ciphertext: ByteArray): BackupFileResult<StagedBackupCiphertext> {
        preflightFailure(ciphertext)?.let { failure -> return failure }
        return withContext(ioDispatcher) {
            ByteArrayInputStream(ciphertext).use { source ->
                stagingArea.stage(source, knownSizeBytes = ciphertext.size.toLong())
            }
        }
    }

    suspend fun stageFromDocument(uri: Uri): BackupFileResult<StagedBackupCiphertext> {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return BackupFileResult.Failed(BackupFileFailureCode.INVALID_DOCUMENT_URI)
        }
        val resolver = contentResolver
            ?: return BackupFileResult.Failed(BackupFileFailureCode.DOCUMENT_UNAVAILABLE)
        return withContext(ioDispatcher) {
            try {
                val descriptor = resolver.openFileDescriptor(uri, "r")
                    ?: return@withContext BackupFileResult.Failed(BackupFileFailureCode.DOCUMENT_UNAVAILABLE)
                val knownSize = try {
                    descriptor.statSize.takeIf { size -> size >= 0L }
                } catch (error: RuntimeException) {
                    descriptor.close()
                    throw error
                }
                var candidate: ByteArray? = null
                try {
                    val readResult = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { source ->
                        stagingArea.readBounded(source, knownSizeBytes = knownSize)
                    }
                    candidate = when (readResult) {
                        is BackupFileResult.Success -> readResult.value
                        is BackupFileResult.Failed -> return@withContext readResult
                    }
                    preflightFailure(candidate)?.let { failure -> return@withContext failure }
                    ByteArrayInputStream(candidate).use { source ->
                        stagingArea.stage(source, knownSizeBytes = candidate.size.toLong())
                    }
                } finally {
                    candidate?.fill(0)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                BackupFileResult.Failed(BackupFileFailureCode.DOCUMENT_UNAVAILABLE)
            } catch (_: IOException) {
                BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
            } catch (_: RuntimeException) {
                BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
            }
        }
    }

    suspend fun readCiphertext(token: String): BackupFileResult<ByteArray> {
        return withContext(ioDispatcher) { stagingArea.read(token) }
    }

    suspend fun writeToDocument(token: String, uri: Uri): BackupFileResult<Long> {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return BackupFileResult.Failed(BackupFileFailureCode.INVALID_DOCUMENT_URI)
        }
        val resolver = contentResolver
            ?: return BackupFileResult.Failed(BackupFileFailureCode.DOCUMENT_UNAVAILABLE)
        return withContext(ioDispatcher) {
            val stageSize = stagingArea.size(token)
            if (stageSize !is BackupFileResult.Success) return@withContext stageSize
            try {
                val descriptor = resolver.openFileDescriptor(uri, "rwt")
                    ?: return@withContext BackupFileResult.Failed(BackupFileFailureCode.DOCUMENT_UNAVAILABLE)
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { target ->
                    stagingArea.copyTo(
                        token = token,
                        target = target,
                        sync = {
                            target.flush()
                            target.fd.sync()
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                BackupFileResult.Failed(BackupFileFailureCode.DOCUMENT_UNAVAILABLE)
            } catch (_: IOException) {
                BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
            } catch (_: RuntimeException) {
                BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
            }
        }
    }

    suspend fun discard(token: String): Boolean {
        return withContext(ioDispatcher) { stagingArea.discard(token) }
    }

    suspend fun cleanupStale(maxAge: Duration = DEFAULT_STALE_AGE): Int {
        return withContext(ioDispatcher) { stagingArea.cleanupStale(maxAge) }
    }

    private fun preflightFailure(ciphertext: ByteArray): BackupFileResult.Failed? {
        return when (val result = envelopeCodec.preflight(ciphertext)) {
            is TransferResult.Success -> null
            is TransferResult.Failure -> BackupFileResult.Failed(
                when (result.failure.code) {
                    TransferFailureCode.TOO_LARGE -> BackupFileFailureCode.CIPHERTEXT_TOO_LARGE
                    TransferFailureCode.UNSUPPORTED_VERSION ->
                        BackupFileFailureCode.UNSUPPORTED_CIPHERTEXT_VERSION

                    else -> BackupFileFailureCode.INVALID_CIPHERTEXT_FORMAT
                },
            )
        }
    }

    companion object {
        val DEFAULT_MAX_CIPHERTEXT_BYTES: Long = GrayinTransferEnvelopeFormat.MAX_ENVELOPE_BYTES.toLong()
        val DEFAULT_STALE_AGE: Duration = Duration.ofHours(24)

        private const val STAGING_DIRECTORY = "transfer/ciphertext-staging"
    }
}

internal fun interface OpaqueBackupTokenGenerator {
    fun nextToken(): String
}

internal class SecureOpaqueBackupTokenGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : OpaqueBackupTokenGenerator {
    override fun nextToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}

internal class CiphertextStagingArea(
    private val rootDirectory: File,
    private val maxCiphertextBytes: Long,
    private val clock: Clock = Clock.systemUTC(),
    private val tokenGenerator: OpaqueBackupTokenGenerator = SecureOpaqueBackupTokenGenerator(),
) {
    init {
        require(maxCiphertextBytes in 1..Int.MAX_VALUE.toLong()) {
            "Ciphertext size bound must fit in one in-memory envelope."
        }
    }

    suspend fun readBounded(
        source: InputStream,
        knownSizeBytes: Long? = null,
    ): BackupFileResult<ByteArray> {
        if (knownSizeBytes == 0L) {
            return BackupFileResult.Failed(BackupFileFailureCode.EMPTY_CIPHERTEXT)
        }
        if (knownSizeBytes != null && knownSizeBytes > maxCiphertextBytes) {
            return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_TOO_LARGE)
        }
        if (knownSizeBytes != null && knownSizeBytes < 0L) {
            return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH)
        }
        return try {
            val output = ByteArrayOutputStream(
                knownSizeBytes?.toInt() ?: COPY_BUFFER_BYTES,
            )
            output.use { target ->
                var copiedBytes = 0L
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        val single = source.read()
                        if (single < 0) break
                        copiedBytes += 1L
                        if (copiedBytes > maxCiphertextBytes) {
                            return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_TOO_LARGE)
                        }
                        target.write(single)
                        continue
                    }
                    copiedBytes += read
                    if (copiedBytes > maxCiphertextBytes) {
                        return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_TOO_LARGE)
                    }
                    target.write(buffer, 0, read)
                }
                if (copiedBytes == 0L) {
                    return BackupFileResult.Failed(BackupFileFailureCode.EMPTY_CIPHERTEXT)
                }
                if (knownSizeBytes != null && copiedBytes != knownSizeBytes) {
                    return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH)
                }
                BackupFileResult.Success(target.toByteArray())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        } catch (_: RuntimeException) {
            BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        }
    }

    suspend fun stage(
        source: InputStream,
        knownSizeBytes: Long? = null,
    ): BackupFileResult<StagedBackupCiphertext> {
        if (knownSizeBytes == 0L) {
            return BackupFileResult.Failed(BackupFileFailureCode.EMPTY_CIPHERTEXT)
        }
        if (knownSizeBytes != null && knownSizeBytes > maxCiphertextBytes) {
            return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_TOO_LARGE)
        }
        if (knownSizeBytes != null && knownSizeBytes < 0L) {
            return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH)
        }
        if (!ensureRootDirectory()) {
            return BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        }
        cleanupStale(EncryptedBackupStagingStore.DEFAULT_STALE_AGE)
        val reserved = reservePartFile()
            ?: return BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        val (token, partFile) = reserved
        val finalFile = finalFile(token)
        try {
            var copiedBytes = 0L
            var streamFailure: BackupFileFailureCode? = null
            FileOutputStream(partFile, false).use { target ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        val single = source.read()
                        if (single < 0) break
                        copiedBytes += 1L
                        if (copiedBytes > maxCiphertextBytes) {
                            streamFailure = BackupFileFailureCode.CIPHERTEXT_TOO_LARGE
                            break
                        }
                        target.write(single)
                        continue
                    }
                    copiedBytes += read
                    if (copiedBytes > maxCiphertextBytes) {
                        streamFailure = BackupFileFailureCode.CIPHERTEXT_TOO_LARGE
                        break
                    }
                    target.write(buffer, 0, read)
                }
                if (streamFailure == null) {
                    target.flush()
                    target.fd.sync()
                }
            }
            streamFailure?.let { failure -> return failedPart(partFile, failure) }
            if (copiedBytes == 0L) {
                return failedPart(partFile, BackupFileFailureCode.EMPTY_CIPHERTEXT)
            }
            if (knownSizeBytes != null && copiedBytes != knownSizeBytes) {
                return failedPart(partFile, BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH)
            }
            try {
                Files.move(
                    partFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                return failedPart(partFile, BackupFileFailureCode.IO_FAILURE)
            }
            finalFile.setLastModified(clock.millis())
            return BackupFileResult.Success(
                StagedBackupCiphertext(token = token, sizeBytes = copiedBytes),
            )
        } catch (error: CancellationException) {
            partFile.delete()
            finalFile.delete()
            throw error
        } catch (_: IOException) {
            partFile.delete()
            finalFile.delete()
            return BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        } catch (_: RuntimeException) {
            partFile.delete()
            finalFile.delete()
            return BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        }
    }

    suspend fun read(token: String): BackupFileResult<ByteArray> {
        val sizeResult = size(token)
        val expectedBytes = when (sizeResult) {
            is BackupFileResult.Success -> sizeResult.value
            is BackupFileResult.Failed -> return sizeResult
        }
        val output = ByteArray(expectedBytes.toInt())
        return try {
            FileInputStream(finalFile(token)).use { source ->
                var offset = 0
                while (offset < output.size) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(output, offset, output.size - offset)
                    if (read < 0) {
                        return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH)
                    }
                    if (read == 0) continue
                    offset += read
                }
                if (source.read() >= 0) {
                    return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH)
                }
            }
            finalFile(token).setLastModified(clock.millis())
            BackupFileResult.Success(output)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        } catch (_: RuntimeException) {
            BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        }
    }

    fun size(token: String): BackupFileResult<Long> {
        if (!token.matches(SAFE_TOKEN)) {
            return BackupFileResult.Failed(BackupFileFailureCode.INVALID_TOKEN)
        }
        val file = finalFile(token)
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) {
            return BackupFileResult.Failed(BackupFileFailureCode.STAGE_NOT_FOUND)
        }
        val size = file.length()
        return when {
            size <= 0L -> BackupFileResult.Failed(BackupFileFailureCode.EMPTY_CIPHERTEXT)
            size > maxCiphertextBytes -> BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_TOO_LARGE)
            else -> BackupFileResult.Success(size)
        }
    }

    suspend fun copyTo(
        token: String,
        target: OutputStream,
        sync: () -> Unit,
    ): BackupFileResult<Long> {
        val sizeResult = size(token)
        if (sizeResult !is BackupFileResult.Success) return sizeResult
        val expectedBytes = sizeResult.value
        return try {
            var copiedBytes = 0L
            FileInputStream(finalFile(token)).use { source ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    copiedBytes += read
                    if (copiedBytes > expectedBytes || copiedBytes > maxCiphertextBytes) {
                        return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH)
                    }
                    target.write(buffer, 0, read)
                }
            }
            if (copiedBytes != expectedBytes) {
                return BackupFileResult.Failed(BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH)
            }
            target.flush()
            sync()
            finalFile(token).setLastModified(clock.millis())
            BackupFileResult.Success(copiedBytes)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        } catch (_: RuntimeException) {
            BackupFileResult.Failed(BackupFileFailureCode.IO_FAILURE)
        }
    }

    fun discard(token: String): Boolean {
        if (!token.matches(SAFE_TOKEN)) return false
        val finalDeleted = !finalFile(token).exists() || finalFile(token).delete()
        val partDeleted = !partFile(token).exists() || partFile(token).delete()
        return finalDeleted && partDeleted
    }

    fun cleanupStale(maxAge: Duration): Int {
        require(!maxAge.isNegative && !maxAge.isZero) { "Stale age must be positive." }
        if (!rootDirectory.isDirectory) return 0
        val cutoffMillis = runCatching { clock.instant().minus(maxAge).toEpochMilli() }
            .getOrElse { Long.MIN_VALUE }
        var deleted = 0
        rootDirectory.listFiles().orEmpty().forEach { file ->
            if (!file.name.matches(SAFE_STAGE_FILE)) return@forEach
            val modifiedAt = runCatching {
                Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS).toMillis()
            }.getOrNull() ?: return@forEach
            if (modifiedAt <= cutoffMillis && file.delete()) deleted += 1
        }
        return deleted
    }

    private fun ensureRootDirectory(): Boolean {
        if ((!rootDirectory.isDirectory && !rootDirectory.mkdirs()) || !rootDirectory.isDirectory) {
            return false
        }
        return !Files.isSymbolicLink(rootDirectory.toPath())
    }

    private fun reservePartFile(): Pair<String, File>? {
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = tokenGenerator.nextToken()
            check(token.matches(SAFE_TOKEN)) { "Opaque backup token generator returned an invalid token." }
            val part = partFile(token)
            if (!finalFile(token).exists() && part.createNewFile()) return token to part
        }
        return null
    }

    private fun finalFile(token: String): File = File(rootDirectory, "$token$CIPHERTEXT_SUFFIX")

    private fun partFile(token: String): File = File(rootDirectory, "$token$PART_SUFFIX")

    private fun failedPart(
        partFile: File,
        code: BackupFileFailureCode,
    ): BackupFileResult.Failed {
        partFile.delete()
        return BackupFileResult.Failed(code)
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_TOKEN_ATTEMPTS = 16
        const val CIPHERTEXT_SUFFIX = ".ciphertext"
        const val PART_SUFFIX = ".part"
        val SAFE_TOKEN = Regex("[a-f0-9]{64}")
        val SAFE_STAGE_FILE = Regex("[a-f0-9]{64}\\.(ciphertext|part)")
    }
}
