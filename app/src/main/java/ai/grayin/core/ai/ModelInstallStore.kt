package ai.grayin.core.ai

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ModelInstallRecord(
    val modelId: String,
    val generation: Long,
    val status: ModelDownloadStatus,
    val progressPercent: Int?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val installedBytes: Long,
    val installedPath: String?,
    val sha256: String?,
    val failureCode: ModelDownloadFailureCode?,
)

enum class ModelPublishResult {
    PUBLISHED,
    STALE_GENERATION,
    FAILED,
}

class ModelInstallStore(
    context: Context,
    private val catalogRepository: ModelCatalogRepository = ModelCatalogRepository(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun selectedModelId(): String {
        return prefs.getString(KEY_SELECTED_MODEL_ID, ModelCatalog.DEFAULT_MODEL_ID)
            ?.takeIf { catalogRepository.entry(it) != null }
            ?: ModelCatalog.DEFAULT_MODEL_ID
    }

    fun selectedEntry(): ModelCatalogEntry {
        return catalogRepository.entry(selectedModelId())
            ?: requireNotNull(catalogRepository.entry(ModelCatalog.DEFAULT_MODEL_ID))
    }

    fun selectModel(modelId: String) {
        require(catalogRepository.entry(modelId) != null) { "Unknown model id." }
        prefs.edit().putString(KEY_SELECTED_MODEL_ID, modelId).apply()
    }

    fun recordFor(entry: ModelCatalogEntry): ModelInstallRecord = synchronized(INSTALL_LOCK) {
        synchronizeConfigurationLocked(entry)
        var status = statusFor(entry.id)
        var installedFile = verifiedStoredInstalledModel(entry)
        if (installedFile == null && hasUntrustedInstallState(entry, status)) {
            deleteUntrustedInstallStateLocked(entry)
            cleanupStagingLocked(entry)
            clearInstallMetadata(entry.id)
            status = ModelDownloadStatus.NOT_DOWNLOADED
        }
        installedFile = verifiedStoredInstalledModel(entry)
        val installedPath = installedFile?.absolutePath
        val effectiveStatus = when {
            installedPath != null && status != ModelDownloadStatus.DOWNLOADING && status != ModelDownloadStatus.QUEUED -> {
                ModelDownloadStatus.READY
            }

            status == ModelDownloadStatus.READY && installedPath == null -> ModelDownloadStatus.NOT_DOWNLOADED
            else -> status
        }
        if (effectiveStatus != status) {
            check(
                prefs.edit()
                    .putString(statusKey(entry.id), effectiveStatus.name)
                    .putInt(progressKey(entry.id), if (effectiveStatus == ModelDownloadStatus.READY) 100 else -1)
                    .commit(),
            ) { "Could not reconcile model install state." }
        }
        ModelInstallRecord(
            modelId = entry.id,
            generation = generation(entry.id),
            status = effectiveStatus,
            progressPercent = prefs.getInt(progressKey(entry.id), -1).takeIf { it in 0..100 },
            downloadedBytes = prefs.getLong(downloadedBytesKey(entry.id), 0L),
            totalBytes = prefs.getLong(totalBytesKey(entry.id), -1L).takeIf { it > 0L },
            installedBytes = installedFile?.length() ?: 0L,
            installedPath = installedPath,
            sha256 = prefs.getString(shaKey(entry.id), null).takeIf { installedPath != null },
            failureCode = storedFailureCode(entry.id),
        )
    }

    fun selectedReadyModelFile(): File? {
        val entry = selectedEntry()
        val record = recordFor(entry)
        val path = record.installedPath ?: return null
        return File(path).takeIf { it.isFile && it.canRead() }
    }

    fun synchronizeConfiguration(entry: ModelCatalogEntry): Long = synchronized(INSTALL_LOCK) {
        synchronizeConfigurationLocked(entry)
        generation(entry.id)
    }

    fun beginInstall(entry: ModelCatalogEntry): Long = synchronized(INSTALL_LOCK) {
        require(entry.downloadConfigured) { "Model download is not enabled." }
        synchronizeConfigurationLocked(entry)
        val nextGeneration = nextGeneration(entry.id)
        check(
            prefs.edit()
                .putLong(generationKey(entry.id), nextGeneration)
                .putString(configurationKey(entry.id), transportFingerprint(entry))
                .putString(statusKey(entry.id), ModelDownloadStatus.QUEUED.name)
                .putInt(progressKey(entry.id), 0)
                .putLong(downloadedBytesKey(entry.id), 0L)
                .putLong(totalBytesKey(entry.id), requireNotNull(entry.expectedDownloadSizeBytes))
                .remove(failureKey(entry.id))
                .commit(),
        ) { "Could not persist model download generation." }
        cleanupStagingLocked(entry)
        nextGeneration
    }

    fun isCurrentGeneration(modelId: String, generation: Long): Boolean = synchronized(INSTALL_LOCK) {
        isCurrentGenerationLocked(modelId, generation)
    }

    fun recordDownloading(
        entry: ModelCatalogEntry,
        generation: Long,
        downloadedBytes: Long,
        totalBytes: Long,
        progressPercent: Int,
    ): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGenerationLocked(entry.id, generation)) return@synchronized false
        if (downloadedBytes < 0L || totalBytes <= 0L || downloadedBytes > totalBytes) return@synchronized false
        val boundedProgress = progressPercent.coerceIn(0, 99)
        if (
            statusFor(entry.id) == ModelDownloadStatus.DOWNLOADING &&
            prefs.getInt(progressKey(entry.id), -1) == boundedProgress
        ) {
            return@synchronized true
        }
        prefs.edit()
            .putString(statusKey(entry.id), ModelDownloadStatus.DOWNLOADING.name)
            .putLong(downloadedBytesKey(entry.id), downloadedBytes)
            .putLong(totalBytesKey(entry.id), totalBytes)
            .putInt(progressKey(entry.id), boundedProgress)
            .remove(failureKey(entry.id))
            .commit()
    }

    fun recordQueuedForRetry(
        entry: ModelCatalogEntry,
        generation: Long,
        failureCode: ModelDownloadFailureCode,
    ): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGenerationLocked(entry.id, generation)) return@synchronized false
        prefs.edit()
            .putString(statusKey(entry.id), ModelDownloadStatus.QUEUED.name)
            .putInt(progressKey(entry.id), 0)
            .putLong(downloadedBytesKey(entry.id), 0L)
            .putLong(totalBytesKey(entry.id), entry.expectedDownloadSizeBytes ?: -1L)
            .putString(failureKey(entry.id), failureCode.storageKey)
            .commit()
    }

    fun recordFailed(
        entry: ModelCatalogEntry,
        generation: Long,
        failureCode: ModelDownloadFailureCode,
    ): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGenerationLocked(entry.id, generation)) return@synchronized false
        recordFailedLocked(entry, failureCode)
    }

    fun publishVerified(
        entry: ModelCatalogEntry,
        generation: Long,
        partFile: File,
    ): ModelPublishResult = synchronized(INSTALL_LOCK) {
        if (!isExpectedPartFile(entry, generation, partFile)) {
            cleanupStagingLocked(entry)
            return@synchronized ModelPublishResult.FAILED
        }
        if (!isCurrentGenerationLocked(entry.id, generation)) {
            deleteFileNoFollowWithinFilesDir(partFile)
            return@synchronized ModelPublishResult.STALE_GENERATION
        }
        val previouslyInstalled = verifiedStoredInstalledModel(entry)
        val destination = modelFile(entry)
        val destinationDir = checkNotNull(destination.parentFile)
        if (
            !ensureDirectoryWithoutSymbolicLinks(destinationDir) ||
            !verifyArtifactFile(entry, partFile)
        ) {
            deleteFileNoFollowWithinFilesDir(partFile)
            recordFailedLocked(entry, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized ModelPublishResult.FAILED
        }
        if (previouslyInstalled?.absolutePath == destination.absolutePath) {
            deleteFileNoFollowWithinFilesDir(partFile)
            if (!verifyInstalledCatalogModel(entry, destination)) {
                recordFailedLocked(entry, ModelDownloadFailureCode.CHECKSUM_MISMATCH)
                return@synchronized ModelPublishResult.FAILED
            }
            return@synchronized if (persistReadyLocked(entry, destination)) {
                ModelPublishResult.PUBLISHED
            } else {
                recordFailedLocked(entry, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
                ModelPublishResult.FAILED
            }
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
            deleteFileNoFollowWithinFilesDir(partFile)
            recordFailedLocked(entry, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized ModelPublishResult.FAILED
        } catch (_: IOException) {
            deleteFileNoFollowWithinFilesDir(partFile)
            recordFailedLocked(entry, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized ModelPublishResult.FAILED
        }
        if (!verifyInstalledCatalogModel(entry, destination)) {
            deleteFileNoFollowWithinFilesDir(destination)
            VERIFIED_FILE_CACHE.remove(destination.absolutePath)
            recordFailedLocked(entry, ModelDownloadFailureCode.CHECKSUM_MISMATCH)
            return@synchronized ModelPublishResult.FAILED
        }
        if (!persistReadyLocked(entry, destination)) {
            deleteFileNoFollowWithinFilesDir(destination)
            VERIFIED_FILE_CACHE.remove(destination.absolutePath)
            recordFailedLocked(entry, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized ModelPublishResult.FAILED
        }
        if (previouslyInstalled != null && previouslyInstalled.absolutePath != destination.absolutePath) {
            deleteFileNoFollowWithinFilesDir(previouslyInstalled)
            VERIFIED_FILE_CACHE.remove(previouslyInstalled.absolutePath)
        }
        pruneRetiredReleaseDirectoriesLocked(entry, destination)
        ModelPublishResult.PUBLISHED
    }

    private fun persistReadyLocked(entry: ModelCatalogEntry, installedFile: File): Boolean {
        val installedBytes = installedFile.length()
        return prefs.edit()
            .putString(statusKey(entry.id), ModelDownloadStatus.READY.name)
            .putInt(progressKey(entry.id), 100)
            .putLong(downloadedBytesKey(entry.id), installedBytes)
            .putLong(totalBytesKey(entry.id), installedBytes)
            .putString(pathKey(entry.id), installedFile.absolutePath)
            .putLong(installedBytesKey(entry.id), installedBytes)
            .putString(shaKey(entry.id), requireNotNull(entry.sha256))
            .remove(failureKey(entry.id))
            .commit()
    }

    fun invalidateForCancel(entry: ModelCatalogEntry): Long = synchronized(INSTALL_LOCK) {
        synchronizeConfigurationLocked(entry)
        val nextGeneration = nextGeneration(entry.id)
        val installedFile = verifiedStoredInstalledModel(entry)
        val status = if (installedFile != null) ModelDownloadStatus.READY else ModelDownloadStatus.NOT_DOWNLOADED
        check(
            prefs.edit()
                .putLong(generationKey(entry.id), nextGeneration)
                .putString(statusKey(entry.id), status.name)
                .putInt(progressKey(entry.id), if (installedFile != null) 100 else -1)
                .putLong(downloadedBytesKey(entry.id), installedFile?.length() ?: 0L)
                .putLong(totalBytesKey(entry.id), installedFile?.length() ?: -1L)
                .remove(failureKey(entry.id))
                .commit(),
        ) { "Could not invalidate model download work." }
        cleanupStagingLocked(entry)
        nextGeneration
    }

    fun deleteInstalled(entry: ModelCatalogEntry, generation: Long): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGenerationLocked(entry.id, generation)) return@synchronized false
        cleanupStagingLocked(entry)
        val installDir = installDir(entry)
        if (!deleteTreeNoFollowWithinFilesDir(installDir)) return@synchronized false
        VERIFIED_FILE_CACHE.keys
            .filter { path -> isPathInside(installDir, File(path)) }
            .forEach(VERIFIED_FILE_CACHE::remove)
        prefs.edit()
            .putString(statusKey(entry.id), ModelDownloadStatus.NOT_DOWNLOADED.name)
            .putInt(progressKey(entry.id), -1)
            .putLong(downloadedBytesKey(entry.id), 0L)
            .putLong(totalBytesKey(entry.id), -1L)
            .remove(pathKey(entry.id))
            .remove(installedBytesKey(entry.id))
            .remove(shaKey(entry.id))
            .remove(failureKey(entry.id))
            .commit()
    }

    fun cleanupStaging(entry: ModelCatalogEntry) = synchronized(INSTALL_LOCK) {
        cleanupStagingLocked(entry)
    }

    fun modelFile(entry: ModelCatalogEntry): File {
        val releaseDigest = entry.sha256?.takeIf { entry.downloadConfigured && it.matches(SHA_256) }
        return if (releaseDigest == null) {
            File(installDir(entry), INSTALLED_MODEL_FILE_NAME)
        } else {
            File(File(File(installDir(entry), RELEASES_DIR_NAME), releaseDigest), INSTALLED_MODEL_FILE_NAME)
        }
    }

    fun partFile(entry: ModelCatalogEntry, generation: Long, workerId: String): File {
        require(generation > 0L) { "Model download generation must be positive." }
        require(workerId.matches(SAFE_WORKER_ID)) { "Model download worker ID is invalid." }
        check(ensureDirectoryWithoutSymbolicLinks(stagingDir(entry))) {
            "Model staging directory is not a safe app-private path."
        }
        return File(stagingDir(entry), "$generation.$workerId$PART_SUFFIX")
    }

    fun discardPartFile(entry: ModelCatalogEntry, generation: Long, partFile: File) = synchronized(INSTALL_LOCK) {
        if (isExpectedPartFile(entry, generation, partFile)) {
            deleteFileNoFollowWithinFilesDir(partFile)
        } else {
            cleanupStagingLocked(entry)
        }
    }

    private fun synchronizeConfigurationLocked(entry: ModelCatalogEntry) {
        val configuredFingerprint = transportFingerprint(entry)
        val storedFingerprint = prefs.getString(configurationKey(entry.id), null)
        val storedStatus = statusFor(entry.id)
        val legacyActiveWork = storedFingerprint == null &&
            (storedStatus == ModelDownloadStatus.QUEUED || storedStatus == ModelDownloadStatus.DOWNLOADING)
        if (storedFingerprint == null && !legacyActiveWork) {
            check(prefs.edit().putString(configurationKey(entry.id), configuredFingerprint).commit()) {
                "Could not persist model transport identity."
            }
            return
        }
        if (storedFingerprint == configuredFingerprint && !legacyActiveWork) return

        val nextGeneration = nextGeneration(entry.id)
        val installedFile = verifiedStoredInstalledModel(entry)
        val editor = prefs.edit()
            .putLong(generationKey(entry.id), nextGeneration)
            .putString(configurationKey(entry.id), configuredFingerprint)
            .putString(
                statusKey(entry.id),
                if (installedFile != null) ModelDownloadStatus.READY.name else ModelDownloadStatus.NOT_DOWNLOADED.name,
            )
            .putInt(progressKey(entry.id), if (installedFile != null) 100 else -1)
            .putLong(downloadedBytesKey(entry.id), installedFile?.length() ?: 0L)
            .putLong(totalBytesKey(entry.id), installedFile?.length() ?: -1L)
            .remove(failureKey(entry.id))
        if (installedFile != null) {
            editor
                .putString(pathKey(entry.id), installedFile.absolutePath)
                .putLong(installedBytesKey(entry.id), installedFile.length())
        } else {
            editor
                .remove(pathKey(entry.id))
                .remove(installedBytesKey(entry.id))
                .remove(shaKey(entry.id))
        }
        check(editor.commit()) { "Could not persist model transport reconfiguration." }
        cleanupStagingLocked(entry)
    }

    private fun recordFailedLocked(
        entry: ModelCatalogEntry,
        failureCode: ModelDownloadFailureCode,
    ): Boolean {
        val installedFile = verifiedStoredInstalledModel(entry)
        val status = if (installedFile != null) ModelDownloadStatus.READY else ModelDownloadStatus.FAILED
        val editor = prefs.edit()
            .putString(statusKey(entry.id), status.name)
            .putInt(progressKey(entry.id), if (installedFile != null) 100 else -1)
            .putLong(downloadedBytesKey(entry.id), installedFile?.length() ?: 0L)
            .putLong(
                totalBytesKey(entry.id),
                installedFile?.length() ?: entry.expectedDownloadSizeBytes ?: -1L,
            )
            .putString(failureKey(entry.id), failureCode.storageKey)
        if (installedFile != null) {
            editor
                .putString(pathKey(entry.id), installedFile.absolutePath)
                .putLong(installedBytesKey(entry.id), installedFile.length())
        } else {
            editor
                .remove(pathKey(entry.id))
                .remove(installedBytesKey(entry.id))
                .remove(shaKey(entry.id))
        }
        return editor.commit()
    }

    private fun installDir(entry: ModelCatalogEntry): File {
        require(entry.id.matches(SAFE_MODEL_ID)) { "Model ID is not safe for app-private storage." }
        return File(appContext.filesDir, "models/${entry.id}")
    }

    private fun stagingDir(entry: ModelCatalogEntry): File = File(installDir(entry), STAGING_DIR_NAME)

    private fun cleanupStagingLocked(entry: ModelCatalogEntry) {
        deleteTreeNoFollowWithinFilesDir(stagingDir(entry))
    }

    private fun isExpectedPartFile(entry: ModelCatalogEntry, generation: Long, partFile: File): Boolean {
        if (!partFile.name.matches(Regex("${generation}\\.[A-Za-z0-9_-]{1,80}\\.part"))) return false
        return runCatching {
            val actualParent = partFile.parentFile?.toPath()?.toAbsolutePath()?.normalize()
            val expectedParent = stagingDir(entry).toPath().toAbsolutePath().normalize()
            actualParent == expectedParent && isSafeAppPrivatePath(partFile)
        }.getOrDefault(false)
    }

    private fun statusFor(modelId: String): ModelDownloadStatus {
        val stored = prefs.getString(statusKey(modelId), null) ?: return ModelDownloadStatus.NOT_DOWNLOADED
        return runCatching { ModelDownloadStatus.valueOf(stored) }.getOrDefault(ModelDownloadStatus.NOT_DOWNLOADED)
    }

    private fun generation(modelId: String): Long = prefs.getLong(generationKey(modelId), 0L).coerceAtLeast(0L)

    private fun nextGeneration(modelId: String): Long {
        val current = generation(modelId)
        check(current < Long.MAX_VALUE) { "Model download generation is exhausted." }
        return current + 1L
    }

    private fun isCurrentGenerationLocked(modelId: String, expectedGeneration: Long): Boolean {
        return expectedGeneration > 0L && generation(modelId) == expectedGeneration
    }

    private fun hasUntrustedInstallState(
        entry: ModelCatalogEntry,
        status: ModelDownloadStatus,
    ): Boolean {
        if (
            entry.downloadConfigured &&
            (status == ModelDownloadStatus.QUEUED || status == ModelDownloadStatus.DOWNLOADING)
        ) {
            return false
        }
        if (
            !entry.downloadConfigured &&
            (status == ModelDownloadStatus.QUEUED || status == ModelDownloadStatus.DOWNLOADING)
        ) {
            return true
        }
        return containsUntrustedFileOrLink(installDir(entry)) ||
            prefs.contains(pathKey(entry.id)) ||
            prefs.contains(installedBytesKey(entry.id)) ||
            prefs.contains(shaKey(entry.id)) ||
            status == ModelDownloadStatus.READY
    }

    private fun verifiedStoredInstalledModel(entry: ModelCatalogEntry): File? {
        val path = prefs.getString(pathKey(entry.id), null) ?: return null
        val expectedBytes = prefs.getLong(installedBytesKey(entry.id), -1L).takeIf { it > 0L } ?: return null
        val expectedSha256 = prefs.getString(shaKey(entry.id), null)
            ?.takeIf { it.matches(SHA_256) }
            ?: return null
        val file = File(path)
        if (!isAllowedInstalledPath(entry, file, expectedSha256)) return null
        return file.takeIf { verifyFile(it, expectedBytes, expectedSha256) }
    }

    private fun isAllowedInstalledPath(
        entry: ModelCatalogEntry,
        file: File,
        expectedSha256: String,
    ): Boolean {
        return runCatching {
            val root = installDir(entry).toPath().toAbsolutePath().normalize()
            val actualFile = file.toPath().toAbsolutePath().normalize()
            val legacyFile = root.resolve(INSTALLED_MODEL_FILE_NAME)
            val releaseFile = File(
                File(File(installDir(entry), RELEASES_DIR_NAME), expectedSha256),
                INSTALLED_MODEL_FILE_NAME,
            ).toPath().toAbsolutePath().normalize()
            isSafeAppPrivatePath(file) && (actualFile == legacyFile || actualFile == releaseFile)
        }.getOrDefault(false)
    }

    private fun verifyFile(
        file: File,
        expectedBytes: Long,
        expectedSha256: String,
        cacheVerifiedIdentity: Boolean = true,
    ): Boolean {
        val before = readStableFileIdentity(file)
        if (before == null || before.sizeBytes != expectedBytes) {
            VERIFIED_FILE_CACHE.remove(file.absolutePath)
            return false
        }
        val cached = VERIFIED_FILE_CACHE[file.absolutePath]
        if (
            cacheVerifiedIdentity &&
            before.cacheable &&
            cached != null &&
            cached.expectedSha256 == expectedSha256 &&
            cached.identity == before
        ) {
            return true
        }
        val verified = runCatching { sha256(file) == expectedSha256 }.getOrDefault(false)
        val after = readStableFileIdentity(file)
        if (!verified || after == null || before != after) {
            VERIFIED_FILE_CACHE.remove(file.absolutePath)
            return false
        }
        if (cacheVerifiedIdentity && after.cacheable) {
            VERIFIED_FILE_CACHE[file.absolutePath] = VerifiedFileCacheEntry(
                expectedSha256 = expectedSha256,
                identity = after,
            )
        } else {
            VERIFIED_FILE_CACHE.remove(file.absolutePath)
        }
        return true
    }

    private fun verifyInstalledCatalogModel(entry: ModelCatalogEntry, file: File): Boolean {
        if (!entry.downloadConfigured) {
            VERIFIED_FILE_CACHE.remove(file.absolutePath)
            return false
        }
        val expectedBytes = entry.expectedDownloadSizeBytes ?: return false
        val expectedSha256 = entry.sha256 ?: return false
        return verifyFile(file, expectedBytes, expectedSha256)
    }

    private fun verifyArtifactFile(entry: ModelCatalogEntry, file: File): Boolean {
        val expectedBytes = entry.expectedDownloadSizeBytes ?: return false
        val expectedSha256 = entry.sha256 ?: return false
        if (!entry.downloadConfigured) return false
        return verifyFile(
            file = file,
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha256,
            cacheVerifiedIdentity = false,
        )
    }

    private fun deleteUntrustedInstallStateLocked(entry: ModelCatalogEntry) {
        val directory = installDir(entry)
        deleteTreeNoFollowWithinFilesDir(directory)
        VERIFIED_FILE_CACHE.keys
            .filter { path -> isPathInside(directory, File(path)) }
            .forEach(VERIFIED_FILE_CACHE::remove)
    }

    private fun pruneRetiredReleaseDirectoriesLocked(entry: ModelCatalogEntry, activeFile: File) {
        val releases = File(installDir(entry), RELEASES_DIR_NAME)
        if (!isSafeAppPrivatePath(releases)) {
            deleteFirstSymbolicLinkWithinFilesDir(releases)
            return
        }
        releases.listFiles().orEmpty().forEach { releaseDirectory ->
            if (!isPathInside(releaseDirectory, activeFile)) {
                VERIFIED_FILE_CACHE.keys
                    .filter { path -> isPathInside(releaseDirectory, File(path)) }
                    .forEach(VERIFIED_FILE_CACHE::remove)
                deleteTreeNoFollowWithinFilesDir(releaseDirectory)
            }
        }
        val legacyFile = File(installDir(entry), INSTALLED_MODEL_FILE_NAME)
        if (legacyFile.absolutePath != activeFile.absolutePath) {
            VERIFIED_FILE_CACHE.remove(legacyFile.absolutePath)
            deleteFileNoFollowWithinFilesDir(legacyFile)
        }
    }

    private fun isPathInside(parent: File, child: File): Boolean {
        return runCatching {
            val parentPath = parent.toPath().toAbsolutePath().normalize()
            val childPath = child.toPath().toAbsolutePath().normalize()
            childPath.startsWith(parentPath)
        }.getOrDefault(false)
    }

    private fun readStableFileIdentity(file: File): StableFileIdentity? {
        if (!isSafeAppPrivatePath(file)) return null
        return runCatching {
            val path = file.toPath()
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!attributes.isRegularFile || attributes.isSymbolicLink || !Files.isReadable(path)) {
                return@runCatching null
            }
            val changeTimeNanos = runCatching {
                (Files.getAttribute(path, UNIX_CHANGE_TIME, LinkOption.NOFOLLOW_LINKS) as? FileTime)
                    ?.to(TimeUnit.NANOSECONDS)
            }.getOrNull()
            StableFileIdentity(
                sizeBytes = attributes.size(),
                lastModifiedNanos = attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                creationTimeNanos = attributes.creationTime().to(TimeUnit.NANOSECONDS),
                fileKey = attributes.fileKey()?.toString(),
                changeTimeNanos = changeTimeNanos,
            )
        }.getOrNull()
    }

    private fun ensureDirectoryWithoutSymbolicLinks(directory: File): Boolean {
        if (!isPathWithinFilesDir(directory) || firstSymbolicLinkWithinFilesDir(directory) != null) return false
        if (!directory.isDirectory && !directory.mkdirs()) return false
        return directory.isDirectory && firstSymbolicLinkWithinFilesDir(directory) == null
    }

    private fun isSafeAppPrivatePath(file: File): Boolean {
        return isPathWithinFilesDir(file) && firstSymbolicLinkWithinFilesDir(file) == null
    }

    private fun isPathWithinFilesDir(file: File): Boolean {
        return runCatching {
            val filesRoot = appContext.filesDir.toPath().toAbsolutePath().normalize()
            val candidate = file.toPath().toAbsolutePath().normalize()
            candidate != filesRoot && candidate.startsWith(filesRoot)
        }.getOrDefault(false)
    }

    private fun firstSymbolicLinkWithinFilesDir(file: File): Path? {
        return runCatching {
            val filesRoot = appContext.filesDir.toPath().toAbsolutePath().normalize()
            val candidate = file.toPath().toAbsolutePath().normalize()
            if (candidate == filesRoot || !candidate.startsWith(filesRoot)) return@runCatching null
            var current = filesRoot
            filesRoot.relativize(candidate).forEach { component ->
                current = current.resolve(component)
                if (Files.isSymbolicLink(current)) return@runCatching current
            }
            null
        }.getOrNull()
    }

    private fun deleteFirstSymbolicLinkWithinFilesDir(file: File): Boolean {
        val symbolicLink = firstSymbolicLinkWithinFilesDir(file) ?: return false
        return runCatching { Files.deleteIfExists(symbolicLink) }.getOrDefault(false)
    }

    private fun deleteFileNoFollowWithinFilesDir(file: File): Boolean {
        if (!isPathWithinFilesDir(file)) return false
        val symbolicLink = firstSymbolicLinkWithinFilesDir(file)
        if (symbolicLink != null) {
            return runCatching { Files.deleteIfExists(symbolicLink) }.getOrDefault(false)
        }
        return runCatching { Files.deleteIfExists(file.toPath()) }.getOrDefault(false)
    }

    private fun deleteTreeNoFollowWithinFilesDir(directory: File): Boolean {
        if (!isPathWithinFilesDir(directory)) return false
        val symbolicLink = firstSymbolicLinkWithinFilesDir(directory)
        if (symbolicLink != null) {
            return runCatching { Files.deleteIfExists(symbolicLink) }.getOrDefault(false)
        }
        val root = directory.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                        if (error != null) throw error
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            true
        }.getOrDefault(false)
    }

    private fun containsUntrustedFileOrLink(directory: File): Boolean {
        if (!isPathWithinFilesDir(directory)) return true
        if (firstSymbolicLinkWithinFilesDir(directory) != null) return true
        val root = directory.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            var found = false
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        found = true
                        return FileVisitResult.TERMINATE
                    }
                },
            )
            found
        }.getOrDefault(true)
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
        return digest.digest().toHex()
    }

    private fun transportFingerprint(entry: ModelCatalogEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            entry.id,
            entry.downloadUrl,
            entry.fileName,
            entry.expectedDownloadSizeBytes?.toString(),
            entry.sha256,
        ).forEach { value ->
            if (value == null) {
                digest.update(0)
            } else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(1)
                digest.update((bytes.size ushr 24).toByte())
                digest.update((bytes.size ushr 16).toByte())
                digest.update((bytes.size ushr 8).toByte())
                digest.update(bytes.size.toByte())
                digest.update(bytes)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun clearInstallMetadata(modelId: String) {
        check(
            prefs.edit()
                .putString(statusKey(modelId), ModelDownloadStatus.NOT_DOWNLOADED.name)
                .remove(progressKey(modelId))
                .remove(downloadedBytesKey(modelId))
                .remove(totalBytesKey(modelId))
                .remove(pathKey(modelId))
                .remove(installedBytesKey(modelId))
                .remove(shaKey(modelId))
                .remove(failureKey(modelId))
                .commit(),
        ) { "Could not clear model install metadata." }
    }

    private fun storedFailureCode(modelId: String): ModelDownloadFailureCode? {
        val stored = prefs.getString(failureKey(modelId), null) ?: return null
        val failureCode = ModelDownloadFailureCode.fromStorageKey(stored)
            ?: ModelDownloadFailureCode.DOWNLOAD_FAILED
        if (failureCode.storageKey != stored) {
            prefs.edit().putString(failureKey(modelId), failureCode.storageKey).commit()
        }
        return failureCode
    }

    private fun generationKey(modelId: String) = "$modelId.generation"
    private fun configurationKey(modelId: String) = "$modelId.transport_fingerprint"
    private fun statusKey(modelId: String) = "$modelId.status"
    private fun progressKey(modelId: String) = "$modelId.progress"
    private fun downloadedBytesKey(modelId: String) = "$modelId.downloaded_bytes"
    private fun totalBytesKey(modelId: String) = "$modelId.total_bytes"
    private fun installedBytesKey(modelId: String) = "$modelId.installed_bytes"
    private fun pathKey(modelId: String) = "$modelId.path"
    private fun shaKey(modelId: String) = "$modelId.sha256"
    private fun failureKey(modelId: String) = "$modelId.failure"

    private companion object {
        const val PREFS_NAME = "grayin_model_installs"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val INSTALLED_MODEL_FILE_NAME = "model.litertlm"
        const val RELEASES_DIR_NAME = "releases"
        const val STAGING_DIR_NAME = "staging"
        const val PART_SUFFIX = ".part"
        val SAFE_WORKER_ID = Regex("[A-Za-z0-9_-]{1,80}")
        val SAFE_MODEL_ID = Regex("[A-Za-z0-9._-]{1,80}")
        val SHA_256 = Regex("[a-f0-9]{64}")
        const val UNIX_CHANGE_TIME = "unix:ctime"
        val INSTALL_LOCK = Any()
        val VERIFIED_FILE_CACHE = ConcurrentHashMap<String, VerifiedFileCacheEntry>()
    }

    private data class VerifiedFileCacheEntry(
        val expectedSha256: String,
        val identity: StableFileIdentity,
    )

    private data class StableFileIdentity(
        val sizeBytes: Long,
        val lastModifiedNanos: Long,
        val creationTimeNanos: Long,
        val fileKey: String?,
        val changeTimeNanos: Long?,
    ) {
        val cacheable: Boolean
            get() = fileKey != null && changeTimeNanos != null
    }
}
