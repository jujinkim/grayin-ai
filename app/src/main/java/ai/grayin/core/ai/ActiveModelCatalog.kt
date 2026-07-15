package ai.grayin.core.ai

import android.content.Context
import android.os.Build
import java.time.Clock

class ModelCatalogRepository(
    context: Context,
    private val manifestStateStore: ModelManifestStateStore = ModelManifestStateStore(context.applicationContext),
    private val appVersionCode: Int = installedAppVersionCode(context.applicationContext),
    private val clock: Clock = Clock.systemUTC(),
    private val remoteTrustIdentity: ModelManifestTrustIdentity? =
        RemoteModelManifestConfigurationValidator.production(appVersionCode, clock)?.trustIdentity,
) {
    fun entries(): List<ModelCatalogEntry> {
        return ModelManifestCatalogProjection.resolve(
            bundledEntries = ModelCatalog.entries,
            manifest = remoteTrustIdentity?.let(manifestStateStore::readAcceptedManifest),
            remoteActivationEnabled = remoteTrustIdentity != null,
            appVersionCode = appVersionCode,
            clock = clock,
        )
    }

    fun entry(modelId: String): ModelCatalogEntry? {
        return entries().firstOrNull { entry -> entry.id == modelId }
    }
}

internal object ModelManifestCatalogProjection {
    fun resolve(
        bundledEntries: List<ModelCatalogEntry>,
        manifest: ModelReleaseManifest?,
        remoteActivationEnabled: Boolean,
        appVersionCode: Int,
        clock: Clock,
    ): List<ModelCatalogEntry> {
        if (!remoteActivationEnabled || manifest == null) return bundledEntries
        if (!isCurrentlyUsable(manifest, appVersionCode, clock)) return bundledEntries
        return overlay(bundledEntries, manifest)
    }

    fun supports(manifest: ModelReleaseManifest): Boolean {
        val modelIds = manifest.models.map(ModelReleaseManifestEntry::modelId)
        return modelIds.isNotEmpty() &&
            modelIds.size <= ModelCatalog.remoteManifestModelIds.size &&
            modelIds.toSet().size == modelIds.size &&
            modelIds.all(ModelCatalog.remoteManifestModelIds::contains)
    }

    fun isCurrentlyUsable(
        manifest: ModelReleaseManifest,
        appVersionCode: Int,
        clock: Clock,
    ): Boolean {
        if (!supports(manifest)) return false
        return ModelReleaseManifestPolicy.validate(
            manifest = manifest,
            appVersionCode = appVersionCode,
            nowEpochSeconds = clock.instant().epochSecond,
        ) == null
    }

    fun overlay(
        bundledEntries: List<ModelCatalogEntry>,
        manifest: ModelReleaseManifest,
    ): List<ModelCatalogEntry> {
        require(supports(manifest)) { "Manifest contains an unsupported model catalog entry." }
        val releaseByModelId = manifest.models.associateBy(ModelReleaseManifestEntry::modelId)
        return bundledEntries.map { bundled ->
            val release = releaseByModelId[bundled.id] ?: return@map bundled
            if (release.deprecated) {
                bundled
            } else {
                bundled.copy(
                    downloadUrl = release.downloadUrl,
                    fileName = release.fileName,
                    approxSizeBytes = release.sizeBytes,
                    expectedDownloadSizeBytes = release.sizeBytes,
                    sha256 = release.sha256,
                    licenseUrl = release.licenseUrl,
                    releaseVersion = release.releaseVersion,
                    manifestSequence = manifest.sequence,
                )
            }
        }
    }
}

internal fun installedAppVersionCode(context: Context): Int {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    return versionCode.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}
