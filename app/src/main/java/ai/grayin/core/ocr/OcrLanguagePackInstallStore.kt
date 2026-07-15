package ai.grayin.core.ocr

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class OcrLanguagePackStatus {
    NOT_INSTALLED,
    QUEUED,
    DOWNLOADING,
    READY,
    FAILED,
}

enum class OcrLanguagePackFailureCode {
    REDIRECT_REJECTED,
    HTTP_REJECTED,
    SERVER_ERROR,
    CONTENT_TYPE_INVALID,
    CONTENT_ENCODING_INVALID,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    NETWORK_OR_IO_FAILURE,
    ATOMIC_INSTALL_FAILED,
}

data class OcrLanguagePackInstallRecord(
    val packId: String,
    val generation: Long,
    val status: OcrLanguagePackStatus,
    val progressPercent: Int?,
    val installedBytes: Long,
    val failureCode: OcrLanguagePackFailureCode?,
)

enum class OcrLanguagePackPublishResult {
    PUBLISHED,
    STALE_GENERATION,
    FAILED,
}

class OcrLanguagePackInstallStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordFor(entry: OcrLanguagePackEntry): OcrLanguagePackInstallRecord = synchronized(INSTALL_LOCK) {
        val storedStatus = storedStatus(entry.id)
        val installedReady = verifyInstalled(entry)
        val effectiveStatus = when {
            storedStatus == OcrLanguagePackStatus.QUEUED || storedStatus == OcrLanguagePackStatus.DOWNLOADING -> {
                storedStatus
            }

            installedReady -> OcrLanguagePackStatus.READY
            storedStatus == OcrLanguagePackStatus.READY -> OcrLanguagePackStatus.NOT_INSTALLED
            else -> storedStatus
        }
        if (effectiveStatus != storedStatus) {
            prefs.edit().putString(statusKey(entry.id), effectiveStatus.name).commit()
        }
        OcrLanguagePackInstallRecord(
            packId = entry.id,
            generation = generation(entry.id),
            status = effectiveStatus,
            progressPercent = prefs.getInt(progressKey(entry.id), -1).takeIf { it in 0..100 },
            installedBytes = if (installedReady) installedFile(entry).length() else 0L,
            failureCode = storedFailure(entry.id),
        )
    }

    fun beginInstall(entry: OcrLanguagePackEntry): Long = synchronized(INSTALL_LOCK) {
        val nextGeneration = generation(entry.id) + 1L
        check(
            prefs.edit()
                .putLong(generationKey(entry.id), nextGeneration)
                .putString(statusKey(entry.id), OcrLanguagePackStatus.QUEUED.name)
                .putInt(progressKey(entry.id), 0)
                .remove(failureKey(entry.id))
                .commit(),
        ) { "Could not persist OCR language-pack generation." }
        nextGeneration
    }

    fun isCurrentGeneration(packId: String, generation: Long): Boolean = synchronized(INSTALL_LOCK) {
        generation > 0L && generation(packId) == generation
    }

    fun recordDownloading(packId: String, generation: Long, progressPercent: Int): Boolean =
        synchronized(INSTALL_LOCK) {
            if (!isCurrentGeneration(packId, generation)) return@synchronized false
            prefs.edit()
                .putString(statusKey(packId), OcrLanguagePackStatus.DOWNLOADING.name)
                .putInt(progressKey(packId), progressPercent.coerceIn(0, 99))
                .remove(failureKey(packId))
                .commit()
        }

    fun recordQueuedForRetry(
        entry: OcrLanguagePackEntry,
        generation: Long,
        failureCode: OcrLanguagePackFailureCode,
    ): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGeneration(entry.id, generation)) return@synchronized false
        prefs.edit()
            .putString(statusKey(entry.id), OcrLanguagePackStatus.QUEUED.name)
            .putInt(progressKey(entry.id), 0)
            .putString(failureKey(entry.id), failureCode.name)
            .commit()
    }

    fun recordFailed(
        entry: OcrLanguagePackEntry,
        generation: Long,
        failureCode: OcrLanguagePackFailureCode,
    ): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGeneration(entry.id, generation)) return@synchronized false
        val status = if (verifyInstalled(entry)) OcrLanguagePackStatus.READY else OcrLanguagePackStatus.FAILED
        prefs.edit()
            .putString(statusKey(entry.id), status.name)
            .putInt(progressKey(entry.id), if (status == OcrLanguagePackStatus.READY) 100 else -1)
            .putString(failureKey(entry.id), failureCode.name)
            .commit()
    }

    fun publishVerified(
        entry: OcrLanguagePackEntry,
        generation: Long,
        partFile: File,
    ): OcrLanguagePackPublishResult = synchronized(INSTALL_LOCK) {
        if (!isCurrentGeneration(entry.id, generation)) {
            partFile.delete()
            return@synchronized OcrLanguagePackPublishResult.STALE_GENERATION
        }
        val destination = installedFile(entry)
        val destinationDir = checkNotNull(destination.parentFile)
        if (
            (!destinationDir.isDirectory && !destinationDir.mkdirs()) ||
            !verifyArtifactFile(entry, partFile)
        ) {
            partFile.delete()
            recordFailed(entry, generation, OcrLanguagePackFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized OcrLanguagePackPublishResult.FAILED
        }
        try {
            VERIFIED_FILE_CACHE.remove(destination.absolutePath)
            Files.move(
                partFile.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            partFile.delete()
            recordFailed(entry, generation, OcrLanguagePackFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized OcrLanguagePackPublishResult.FAILED
        } catch (_: IOException) {
            partFile.delete()
            recordFailed(entry, generation, OcrLanguagePackFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized OcrLanguagePackPublishResult.FAILED
        }
        if (!verifyInstalled(entry)) {
            destination.delete()
            recordFailed(entry, generation, OcrLanguagePackFailureCode.CHECKSUM_MISMATCH)
            return@synchronized OcrLanguagePackPublishResult.FAILED
        }
        check(
            prefs.edit()
                .putString(statusKey(entry.id), OcrLanguagePackStatus.READY.name)
                .putInt(progressKey(entry.id), 100)
                .remove(failureKey(entry.id))
                .commit(),
        ) { "Could not persist installed OCR language-pack state." }
        OcrLanguagePackPublishResult.PUBLISHED
    }

    fun invalidateForCancel(entry: OcrLanguagePackEntry): Long = synchronized(INSTALL_LOCK) {
        val nextGeneration = generation(entry.id) + 1L
        val status = if (verifyInstalled(entry)) OcrLanguagePackStatus.READY else OcrLanguagePackStatus.NOT_INSTALLED
        check(
            prefs.edit()
                .putLong(generationKey(entry.id), nextGeneration)
                .putString(statusKey(entry.id), status.name)
                .putInt(progressKey(entry.id), if (status == OcrLanguagePackStatus.READY) 100 else -1)
                .remove(failureKey(entry.id))
                .commit(),
        ) { "Could not invalidate OCR language-pack work." }
        nextGeneration
    }

    fun deleteInstalled(entry: OcrLanguagePackEntry): Boolean = synchronized(INSTALL_LOCK) {
        cleanupStaging(entry.id)
        val destination = installedFile(entry)
        if (destination.exists() && !destination.delete()) return@synchronized false
        VERIFIED_FILE_CACHE.remove(destination.absolutePath)
        check(
            prefs.edit()
                .putString(statusKey(entry.id), OcrLanguagePackStatus.NOT_INSTALLED.name)
                .putInt(progressKey(entry.id), -1)
                .remove(failureKey(entry.id))
                .commit(),
        ) { "Could not clear OCR language-pack state." }
        true
    }

    fun cleanupStaging(packId: String) = synchronized(INSTALL_LOCK) {
        stagingDir().listFiles()
            .orEmpty()
            .filter { file -> file.name.startsWith("$packId.") && file.name.endsWith(PART_SUFFIX) }
            .forEach(File::delete)
    }

    fun partFile(entry: OcrLanguagePackEntry, generation: Long, workerId: String): File {
        require(generation > 0L) { "OCR language-pack generation must be positive." }
        require(workerId.matches(SAFE_WORKER_ID)) { "OCR language-pack worker ID is invalid." }
        return File(stagingDir(), "${entry.id}.$generation.$workerId$PART_SUFFIX")
    }

    fun installedFile(entry: OcrLanguagePackEntry): File = File(tessdataDir(), entry.fileName)

    fun tesseractDataPath(): File = tesseractDir()

    private fun verifyInstalled(entry: OcrLanguagePackEntry): Boolean {
        val file = installedFile(entry)
        if (
            !file.isFile ||
            !file.canRead() ||
            file.length() != entry.expectedSizeBytes ||
            Files.isSymbolicLink(file.toPath())
        ) {
            VERIFIED_FILE_CACHE.remove(file.absolutePath)
            return false
        }
        val cached = VERIFIED_FILE_CACHE[file.absolutePath]
        if (
            cached != null &&
            cached.expectedSha256 == entry.sha256 &&
            cached.sizeBytes == file.length() &&
            cached.lastModifiedMillis == file.lastModified()
        ) {
            return true
        }
        val verified = runCatching { sha256(file) == entry.sha256 }.getOrDefault(false)
        if (verified) {
            VERIFIED_FILE_CACHE[file.absolutePath] = VerifiedFileCacheEntry(
                expectedSha256 = entry.sha256,
                sizeBytes = file.length(),
                lastModifiedMillis = file.lastModified(),
            )
        } else {
            VERIFIED_FILE_CACHE.remove(file.absolutePath)
        }
        return verified
    }

    private fun verifyArtifactFile(entry: OcrLanguagePackEntry, file: File): Boolean {
        if (!file.isFile || !file.canRead() || file.length() != entry.expectedSizeBytes) return false
        if (Files.isSymbolicLink(file.toPath())) return false
        return runCatching { sha256(file) == entry.sha256 }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun storedStatus(packId: String): OcrLanguagePackStatus {
        val stored = prefs.getString(statusKey(packId), null) ?: return OcrLanguagePackStatus.NOT_INSTALLED
        return runCatching { OcrLanguagePackStatus.valueOf(stored) }
            .getOrDefault(OcrLanguagePackStatus.NOT_INSTALLED)
    }

    private fun storedFailure(packId: String): OcrLanguagePackFailureCode? {
        val stored = prefs.getString(failureKey(packId), null) ?: return null
        return runCatching { OcrLanguagePackFailureCode.valueOf(stored) }.getOrNull()
    }

    private fun generation(packId: String): Long = prefs.getLong(generationKey(packId), 0L).coerceAtLeast(0L)

    private fun tesseractDir(): File = File(appContext.noBackupFilesDir, "ocr/tesseract")

    private fun tessdataDir(): File = File(tesseractDir(), "tessdata")

    private fun stagingDir(): File = File(tesseractDir(), "staging")

    private fun generationKey(packId: String) = "$packId.generation"
    private fun statusKey(packId: String) = "$packId.status"
    private fun progressKey(packId: String) = "$packId.progress"
    private fun failureKey(packId: String) = "$packId.failure"

    private companion object {
        const val PREFS_NAME = "grayin_ocr_language_packs"
        const val PART_SUFFIX = ".part"
        val SAFE_WORKER_ID = Regex("[A-Za-z0-9_-]{1,80}")
        val INSTALL_LOCK = Any()
        val VERIFIED_FILE_CACHE = mutableMapOf<String, VerifiedFileCacheEntry>()
    }

    private data class VerifiedFileCacheEntry(
        val expectedSha256: String,
        val sizeBytes: Long,
        val lastModifiedMillis: Long,
    )
}
