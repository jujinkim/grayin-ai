package ai.grayin.core.ai

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun catalogExposesDedicatedAndGemmaRuntimeDownloadChoices() {
        assertEquals(3, ModelCatalog.entries.size)
        assertNotNull(ModelCatalog.entry(ModelCatalog.GRAYIN_DEDICATED_MODEL_ID))
        assertNotNull(ModelCatalog.entry("gemma-4-E2B-it"))
        assertNotNull(ModelCatalog.entry("gemma-4-E4B-it"))
    }

    @Test
    fun grayinDedicatedModelStaysDisabledUntilServerUrlExists() {
        val entry = requireNotNull(ModelCatalog.entry(ModelCatalog.GRAYIN_DEDICATED_MODEL_ID))

        assertTrue(entry.recommended)
        assertEquals(ModelProvider.GRAYIN_SERVER, entry.provider)
        assertTrue(entry.displayName.contains("WI8/AFP32"))
        assertTrue(entry.fileName.contains("wi8-afp32"))
        assertNull(entry.downloadUrl)
        assertNull(entry.sha256)
    }

    @Test
    fun officialGemmaModelsExposePagesButDisableDownloadsUntilIntegrityMetadataExists() {
        val e2b = requireNotNull(ModelCatalog.entry("gemma-4-E2B-it"))
        val e4b = requireNotNull(ModelCatalog.entry("gemma-4-E4B-it"))

        assertEquals(ModelProvider.HUGGING_FACE, e2b.provider)
        assertEquals(ModelProvider.HUGGING_FACE, e4b.provider)
        assertEquals("Apache-2.0", e2b.licenseLabel)
        assertEquals("Apache-2.0", e4b.licenseLabel)
        assertTrue(requireNotNull(e2b.downloadPageUrl).contains("litert-community/gemma-4-E2B-it-litert-lm"))
        assertTrue(requireNotNull(e4b.downloadPageUrl).contains("litert-community/gemma-4-E4B-it-litert-lm"))
        assertNull(e2b.downloadUrl)
        assertNull(e4b.downloadUrl)
        assertNull(e2b.expectedDownloadSizeBytes)
        assertNull(e4b.expectedDownloadSizeBytes)
        assertNull(e2b.sha256)
        assertNull(e4b.sha256)
        assertFalse(e2b.downloadConfigured)
        assertFalse(e4b.downloadConfigured)
        assertNull(e2b.artifactSpecOrNull())
        assertNull(e4b.artifactSpecOrNull())
        assertTrue(e2b.approxSizeBytes > 2_000_000_000L)
        assertTrue(e4b.approxSizeBytes > e2b.approxSizeBytes)
        assertFalse(e2b.recommended)
        assertFalse(e4b.recommended)
    }

    @Test
    fun everyCurrentCatalogEntryFailsClosedBeforeNetworkDownload() {
        ModelCatalog.entries.forEach { entry ->
            assertFalse(entry.transportMetadataComplete)
            assertFalse(entry.downloadConfigured)
            assertNull(entry.artifactSpecOrNull())
        }
    }

    @Test
    fun completeFixedMetadataCreatesSpecAndEveryIncompleteVariantFailsClosed() {
        val digest = "a".repeat(64)
        val configured = testEntry(
            downloadUrl = "https://artifacts.example.test/models/model-v1.litertlm",
            exactSize = 123L,
            sha256 = digest,
        )

        assertTrue(configured.transportMetadataComplete)
        assertTrue(configured.downloadConfigured)
        assertTrue(ModelDownloadReleaseGate.GENERATION_FENCING_READY)
        val spec = requireNotNull(configured.artifactSpecOrNull())
        assertEquals(123L, spec.expectedSizeBytes)
        assertEquals(digest, spec.sha256)

        val invalidEntries = listOf(
            testEntry(downloadUrl = null, exactSize = 123L, sha256 = digest),
            testEntry(downloadUrl = "http://artifacts.example.test/model", exactSize = 123L, sha256 = digest),
            testEntry(downloadUrl = "https://artifacts.example.test/model?q=1", exactSize = 123L, sha256 = digest),
            testEntry(downloadUrl = "https://artifacts.example.test/model", exactSize = null, sha256 = digest),
            testEntry(downloadUrl = "https://artifacts.example.test/model", exactSize = 0L, sha256 = digest),
            testEntry(downloadUrl = "https://artifacts.example.test/model", exactSize = 123L, sha256 = null),
            testEntry(downloadUrl = "https://artifacts.example.test/model", exactSize = 123L, sha256 = "A".repeat(64)),
        )
        invalidEntries.forEach { entry ->
            assertFalse(entry.transportMetadataComplete)
            assertFalse(entry.downloadConfigured)
            assertNull(entry.artifactSpecOrNull())
        }
    }

    @Test
    fun acceptedManifestProjectsOnlyTheDedicatedCatalogEntry() {
        val release = validManifestEntry()
        val manifest = validManifest(models = listOf(release))

        val entries = ModelManifestCatalogProjection.overlay(ModelCatalog.entries, manifest)
        val dedicated = requireNotNull(entries.firstOrNull { it.id == ModelCatalog.GRAYIN_DEDICATED_MODEL_ID })

        assertTrue(dedicated.downloadConfigured)
        assertEquals(release.downloadUrl, dedicated.downloadUrl)
        assertEquals(release.fileName, dedicated.fileName)
        assertEquals(release.sizeBytes, dedicated.expectedDownloadSizeBytes)
        assertEquals(release.sha256, dedicated.sha256)
        assertEquals(release.releaseVersion, dedicated.releaseVersion)
        assertEquals(manifest.sequence, dedicated.manifestSequence)
        assertNull(requireNotNull(entries.firstOrNull { it.id == ModelCatalog.DEFAULT_MODEL_ID }).downloadUrl)
    }

    @Test
    fun unsupportedExpiredAndDeprecatedManifestDataCannotEnableTransport() {
        val now = Instant.parse("2026-07-15T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val unsupported = validManifest(
            models = listOf(validManifestEntry().copy(modelId = ModelCatalog.DEFAULT_MODEL_ID)),
        )
        assertFalse(ModelManifestCatalogProjection.supports(unsupported))
        assertFalse(ModelManifestCatalogProjection.supports(validManifest(models = emptyList())))

        val expired = validManifest(
            issuedAtEpochSeconds = now.minusSeconds(3_600L).epochSecond,
            expiresAtEpochSeconds = now.minusSeconds(301L).epochSecond,
        )
        assertFalse(ModelManifestCatalogProjection.isCurrentlyUsable(expired, appVersionCode = 1, clock = clock))

        val unsafeStoredEntry = validManifest(
            models = listOf(
                validManifestEntry().copy(
                    downloadUrl = "https://models.example.test/releases/v1/model.litertlm?device=1",
                ),
            ),
        )
        assertFalse(
            ModelManifestCatalogProjection.isCurrentlyUsable(
                unsafeStoredEntry,
                appVersionCode = 1,
                clock = clock,
            ),
        )

        val obsoleteRuntimeEntry = validManifest(
            models = listOf(validManifestEntry().copy(liteRtLmRuntimeVersion = "0.12.0")),
        )
        assertFalse(
            ModelManifestCatalogProjection.isCurrentlyUsable(
                obsoleteRuntimeEntry,
                appVersionCode = 1,
                clock = clock,
            ),
        )

        val newerAppOnly = validManifest().copy(minimumAppVersionCode = 2)
        assertFalse(
            ModelManifestCatalogProjection.isCurrentlyUsable(
                newerAppOnly,
                appVersionCode = 1,
                clock = clock,
            ),
        )

        val deprecated = validManifest(models = listOf(validManifestEntry().copy(deprecated = true)))
        val projected = ModelManifestCatalogProjection.overlay(ModelCatalog.entries, deprecated)
        val dedicated = requireNotNull(projected.firstOrNull { it.id == ModelCatalog.GRAYIN_DEDICATED_MODEL_ID })
        assertFalse(dedicated.downloadConfigured)
        assertNull(dedicated.downloadUrl)
        assertNull(dedicated.sha256)
    }

    @Test
    fun disabledProductionTrustDoesNotProjectResidualAcceptedManifest() {
        val now = Instant.parse("2026-07-15T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val manifest = validManifest()
        val invalidConfiguration = RemoteModelManifestConfigurationValidator.validate(
            endpointUrl = "https://models.example.test/releases/manifest.json",
            publicKeyX509Base64 = "invalid-key",
            expectedKeyId = "test-key",
            appVersionCode = 1,
            clock = clock,
        )

        val resolved = ModelManifestCatalogProjection.resolve(
            bundledEntries = ModelCatalog.entries,
            manifest = manifest,
            remoteActivationEnabled = invalidConfiguration != null,
            appVersionCode = 1,
            clock = clock,
        )

        assertFalse(RemoteModelManifestConfiguration.configured)
        assertNull(invalidConfiguration)
        assertEquals(ModelCatalog.entries, resolved)
        assertFalse(
            requireNotNull(resolved.firstOrNull { it.id == ModelCatalog.GRAYIN_DEDICATED_MODEL_ID }).downloadConfigured,
        )
    }

    @Test
    fun modelFailureStorageKeysAreClosedAndUnique() {
        assertEquals(
            ModelDownloadFailureCode.entries.size,
            ModelDownloadFailureCode.entries.map { code -> code.storageKey }.toSet().size,
        )
        ModelDownloadFailureCode.entries.forEach { code ->
            assertEquals(code, ModelDownloadFailureCode.fromStorageKey(code.storageKey))
        }
        assertNull(ModelDownloadFailureCode.fromStorageKey("http_500"))
    }

    private fun testEntry(
        downloadUrl: String?,
        exactSize: Long?,
        sha256: String?,
    ): ModelCatalogEntry {
        return ModelCatalogEntry(
            id = "test-model-v1",
            displayName = "Test model",
            provider = ModelProvider.GRAYIN_SERVER,
            providerLabel = "Test provider",
            downloadPageUrl = null,
            downloadUrl = downloadUrl,
            fileName = "model-v1.litertlm",
            approxSizeBytes = 123L,
            expectedDownloadSizeBytes = exactSize,
            sha256 = sha256,
            recommendedRamGb = 4,
            licenseLabel = "Test terms",
            licenseUrl = "https://artifacts.example.test/terms",
            recommended = false,
        )
    }

    private fun validManifest(
        models: List<ModelReleaseManifestEntry> = listOf(validManifestEntry()),
        issuedAtEpochSeconds: Long = Instant.parse("2026-07-14T23:59:00Z").epochSecond,
        expiresAtEpochSeconds: Long = Instant.parse("2026-07-16T00:00:00Z").epochSecond,
    ): ModelReleaseManifest {
        return ModelReleaseManifest(
            schemaVersion = SignedModelManifestVerifier.SUPPORTED_SCHEMA_VERSION,
            sequence = 7L,
            issuedAtEpochSeconds = issuedAtEpochSeconds,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
            minimumAppVersionCode = 1,
            models = models,
        )
    }

    private fun validManifestEntry(): ModelReleaseManifestEntry {
        return ModelReleaseManifestEntry(
            modelId = ModelCatalog.GRAYIN_DEDICATED_MODEL_ID,
            releaseVersion = "1.0.0",
            fileName = "grayin-gemma-4-E2B-it-wi8-afp32-v1.litertlm",
            downloadUrl = "https://models.example.test/releases/v1/model.litertlm",
            sizeBytes = SignedModelManifestVerifier.MIN_MODEL_BYTES,
            sha256 = "a".repeat(64),
            licenseUrl = "https://models.example.test/terms/v1",
            liteRtLmRuntimeVersion = SignedModelManifestVerifier.SUPPORTED_LITERT_LM_RUNTIME_VERSION,
            containerMajorVersion = SignedModelManifestVerifier.SUPPORTED_CONTAINER_MAJOR_VERSION,
            deprecated = false,
        )
    }
}
