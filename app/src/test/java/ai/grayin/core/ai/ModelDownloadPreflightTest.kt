package ai.grayin.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ModelDownloadPreflightTest {
    @Test
    fun staleGenerationCannotOpenManifestTransport() = runBlocking {
        var manifestOpenCount = 0

        val result = ModelDownloadPreflight.run(
            modelId = "test-model",
            generation = 6L,
            isCurrentGeneration = { _, _ -> false },
            refreshManifest = { manifestOpenCount += 1 },
            resolveEntry = { error("Stale work must not resolve catalog transport.") },
            synchronizeConfiguration = { error("Stale work must not synchronize catalog transport.") },
        )

        assertEquals(ModelDownloadPreflightResult.STALE_GENERATION, result)
        assertEquals(0, manifestOpenCount)
    }

    @Test
    fun manifestTransportChangeIsFencedBeforeArtifactTransport() = runBlocking {
        val entry = configuredEntry()
        var current = true
        var manifestOpenCount = 0

        val result = ModelDownloadPreflight.run(
            modelId = entry.id,
            generation = 7L,
            isCurrentGeneration = { _, _ -> current },
            refreshManifest = { manifestOpenCount += 1 },
            resolveEntry = { entry },
            synchronizeConfiguration = {
                current = false
                8L
            },
        )

        assertEquals(ModelDownloadPreflightResult.STALE_GENERATION, result)
        assertEquals(1, manifestOpenCount)
    }

    @Test
    fun currentGenerationResolvesOnlyAfterManifestAndConfigurationChecks() = runBlocking {
        val entry = configuredEntry()
        val calls = mutableListOf<String>()

        val result = ModelDownloadPreflight.run(
            modelId = entry.id,
            generation = 7L,
            isCurrentGeneration = { _, _ ->
                calls += "generation"
                true
            },
            refreshManifest = { calls += "manifest" },
            resolveEntry = {
                calls += "catalog"
                entry
            },
            synchronizeConfiguration = {
                calls += "configuration"
                7L
            },
        )

        assertSame(entry, (result as ModelDownloadPreflightResult.Ready).entry)
        assertEquals(
            listOf("generation", "manifest", "catalog", "configuration", "generation"),
            calls,
        )
    }

    private fun configuredEntry(): ModelCatalogEntry {
        return ModelCatalogEntry(
            id = "test-model",
            displayName = "Test model",
            provider = ModelProvider.GRAYIN_SERVER,
            providerLabel = "Test provider",
            downloadPageUrl = null,
            downloadUrl = "https://models.example.test/releases/model.litertlm",
            fileName = "model.litertlm",
            approxSizeBytes = 1_048_576L,
            expectedDownloadSizeBytes = 1_048_576L,
            sha256 = "a".repeat(64),
            recommendedRamGb = 4,
            licenseLabel = "Test terms",
            licenseUrl = "https://models.example.test/terms",
            recommended = false,
        )
    }
}
