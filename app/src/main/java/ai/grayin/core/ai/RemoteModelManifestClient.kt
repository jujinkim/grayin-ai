package ai.grayin.core.ai

import android.content.Context
import ai.grayin.core.artifact.ArtifactDownloadFailureCode
import ai.grayin.core.artifact.BoundedDocumentDownloadResult
import ai.grayin.core.artifact.BoundedFixedEndpointDownloader
import ai.grayin.core.artifact.FixedEndpointDocumentSpec
import java.time.Clock
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

internal enum class ModelManifestRefreshFailureCode {
    NOT_CONFIGURED,
    CONFIGURATION_INVALID,
    REDIRECT_REJECTED,
    HTTP_REJECTED,
    SERVER_ERROR,
    CONTENT_TYPE_INVALID,
    CONTENT_ENCODING_INVALID,
    RESPONSE_TOO_LARGE,
    NETWORK_OR_IO_FAILURE,
    VERIFICATION_REJECTED,
    CATALOG_UNSUPPORTED,
    ROLLBACK_REJECTED,
    EQUIVOCATION_REJECTED,
    STATE_INVALID,
    PERSISTENCE_FAILURE,
}

internal sealed interface ModelManifestRefreshResult {
    data class Activated(
        val sequence: Long,
        val replay: Boolean,
    ) : ModelManifestRefreshResult

    data class Unavailable(
        val code: ModelManifestRefreshFailureCode,
        val retryable: Boolean = false,
    ) : ModelManifestRefreshResult
}

internal data class RemoteModelManifestClientConfiguration(
    val endpoint: FixedEndpointDocumentSpec,
    val verifier: SignedModelManifestVerifier,
) {
    val trustIdentity: ModelManifestTrustIdentity = requireNotNull(verifier.trustIdentity)
}

internal object RemoteModelManifestConfigurationValidator {
    fun production(
        appVersionCode: Int,
        clock: Clock,
    ): RemoteModelManifestClientConfiguration? {
        return validate(
            endpointUrl = RemoteModelManifestConfiguration.endpointUrl,
            publicKeyX509Base64 = RemoteModelManifestConfiguration.publicKeyX509Base64,
            expectedKeyId = RemoteModelManifestConfiguration.expectedKeyId,
            appVersionCode = appVersionCode,
            clock = clock,
        )
    }

    fun validate(
        endpointUrl: String?,
        publicKeyX509Base64: String?,
        expectedKeyId: String,
        appVersionCode: Int,
        clock: Clock,
    ): RemoteModelManifestClientConfiguration? = runCatching {
        val fixedEndpointUrl = requireNotNull(endpointUrl)
        val encodedPublicKey = requireNotNull(publicKeyX509Base64)
        require(encodedPublicKey.length <= MAX_PUBLIC_KEY_BASE64_CHARS)
        val verifier = SignedModelManifestVerifier(
            expectedKeyId = expectedKeyId,
            publicKeyX509 = Base64.getDecoder().decode(encodedPublicKey),
            appVersionCode = appVersionCode,
            clock = clock,
        )
        require(verifier.trustConfigured)
        RemoteModelManifestClientConfiguration(
            endpoint = FixedEndpointDocumentSpec(
                id = MANIFEST_DOCUMENT_ID,
                url = fixedEndpointUrl,
                maximumSizeBytes = SignedModelManifestVerifier.MAX_ENVELOPE_BYTES,
                allowedContentTypes = setOf(JSON_CONTENT_TYPE),
                acceptContentType = JSON_CONTENT_TYPE,
            ),
            verifier = verifier,
        )
    }.getOrNull()

    private const val MANIFEST_DOCUMENT_ID = "model-release-manifest"
    private const val JSON_CONTENT_TYPE = "application/json"
    private const val MAX_PUBLIC_KEY_BASE64_CHARS = 512
}

internal class RemoteModelManifestClient(
    private val configuration: RemoteModelManifestClientConfiguration?,
    private val configurationFailure: ModelManifestRefreshFailureCode = ModelManifestRefreshFailureCode.NOT_CONFIGURED,
    private val downloader: BoundedFixedEndpointDownloader = BoundedFixedEndpointDownloader(),
    private val acceptanceStore: ModelManifestAcceptanceStore,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val uiRefreshGate: ModelManifestRefreshGate = PROCESS_UI_REFRESH_GATE,
) {
    suspend fun refreshIfDue(
        minimumIntervalNanos: Long = DEFAULT_UI_REFRESH_INTERVAL_NANOS,
    ): ModelManifestRefreshResult? {
        require(minimumIntervalNanos > 0L) { "Manifest refresh interval must be positive." }
        val now = monotonicNanos()
        val shouldRefresh = uiRefreshGate.tryAcquire(now, minimumIntervalNanos)
        return if (shouldRefresh) refresh() else null
    }

    suspend fun refresh(): ModelManifestRefreshResult {
        val activeConfiguration = configuration
            ?: return unavailable(configurationFailure)
        return try {
            when (val download = downloader.fetch(activeConfiguration.endpoint)) {
                is BoundedDocumentDownloadResult.Failed -> unavailable(
                    code = download.code.toRefreshFailureCode(),
                    retryable = download.retryable,
                )

                is BoundedDocumentDownloadResult.Available -> activate(
                    activeConfiguration.verifier.verify(download.bytes),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            unavailable(ModelManifestRefreshFailureCode.NETWORK_OR_IO_FAILURE, retryable = true)
        }
    }

    private fun activate(verification: ModelManifestVerificationResult): ModelManifestRefreshResult {
        val verified = verification as? ModelManifestVerificationResult.Verified
            ?: return unavailable(ModelManifestRefreshFailureCode.VERIFICATION_REJECTED)
        if (!ModelManifestCatalogProjection.supports(verified.manifest)) {
            return unavailable(ModelManifestRefreshFailureCode.CATALOG_UNSUPPORTED)
        }
        return when (acceptanceStore.accept(verified)) {
            ModelManifestAcceptanceDecision.ACCEPT_NEW -> ModelManifestRefreshResult.Activated(
                sequence = verified.manifest.sequence,
                replay = false,
            )

            ModelManifestAcceptanceDecision.ACCEPT_REPLAY -> ModelManifestRefreshResult.Activated(
                sequence = verified.manifest.sequence,
                replay = true,
            )

            ModelManifestAcceptanceDecision.REJECT_ROLLBACK ->
                unavailable(ModelManifestRefreshFailureCode.ROLLBACK_REJECTED)

            ModelManifestAcceptanceDecision.REJECT_EQUIVOCATION ->
                unavailable(ModelManifestRefreshFailureCode.EQUIVOCATION_REJECTED)

            ModelManifestAcceptanceDecision.REJECT_STATE_INVALID ->
                unavailable(ModelManifestRefreshFailureCode.STATE_INVALID)

            ModelManifestAcceptanceDecision.REJECT_PERSISTENCE_FAILURE ->
                unavailable(ModelManifestRefreshFailureCode.PERSISTENCE_FAILURE)
        }
    }

    private fun ArtifactDownloadFailureCode.toRefreshFailureCode(): ModelManifestRefreshFailureCode {
        return when (this) {
            ArtifactDownloadFailureCode.REDIRECT_REJECTED -> ModelManifestRefreshFailureCode.REDIRECT_REJECTED
            ArtifactDownloadFailureCode.HTTP_REJECTED -> ModelManifestRefreshFailureCode.HTTP_REJECTED
            ArtifactDownloadFailureCode.SERVER_ERROR -> ModelManifestRefreshFailureCode.SERVER_ERROR
            ArtifactDownloadFailureCode.CONTENT_TYPE_INVALID -> ModelManifestRefreshFailureCode.CONTENT_TYPE_INVALID
            ArtifactDownloadFailureCode.CONTENT_ENCODING_INVALID -> ModelManifestRefreshFailureCode.CONTENT_ENCODING_INVALID
            ArtifactDownloadFailureCode.SIZE_MISMATCH -> ModelManifestRefreshFailureCode.RESPONSE_TOO_LARGE
            ArtifactDownloadFailureCode.CHECKSUM_MISMATCH -> ModelManifestRefreshFailureCode.VERIFICATION_REJECTED
            ArtifactDownloadFailureCode.IO_FAILURE -> ModelManifestRefreshFailureCode.NETWORK_OR_IO_FAILURE
        }
    }

    private fun unavailable(
        code: ModelManifestRefreshFailureCode,
        retryable: Boolean = false,
    ): ModelManifestRefreshResult.Unavailable {
        return ModelManifestRefreshResult.Unavailable(code, retryable)
    }

    companion object {
        fun production(
            context: Context,
            clock: Clock = Clock.systemUTC(),
        ): RemoteModelManifestClient {
            val appContext = context.applicationContext
            val stateStore = ModelManifestStateStore(appContext)
            if (
                RemoteModelManifestConfiguration.endpointUrl == null &&
                RemoteModelManifestConfiguration.publicKeyX509Base64 == null
            ) {
                return RemoteModelManifestClient(
                    configuration = null,
                    acceptanceStore = stateStore,
                )
            }
            val appVersionCode = installedAppVersionCode(appContext)
            val configuration = RemoteModelManifestConfigurationValidator.production(appVersionCode, clock)
            if (configuration == null) {
                return RemoteModelManifestClient(
                    configuration = null,
                    configurationFailure = ModelManifestRefreshFailureCode.CONFIGURATION_INVALID,
                    acceptanceStore = stateStore,
                )
            }
            return RemoteModelManifestClient(
                configuration = configuration,
                acceptanceStore = stateStore,
            )
        }

        private const val DEFAULT_UI_REFRESH_INTERVAL_NANOS = 15L * 60L * 1_000_000_000L
        private val PROCESS_UI_REFRESH_GATE = ModelManifestRefreshGate()
    }
}

internal class ModelManifestRefreshGate {
    private val lock = Any()
    private var lastAttemptNanos: Long? = null

    fun tryAcquire(nowNanos: Long, minimumIntervalNanos: Long): Boolean {
        require(minimumIntervalNanos > 0L) { "Manifest refresh interval must be positive." }
        return synchronized(lock) {
            val elapsed = lastAttemptNanos?.let { lastAttempt -> nowNanos - lastAttempt }
            if (elapsed != null && elapsed >= 0L && elapsed < minimumIntervalNanos) {
                false
            } else {
                lastAttemptNanos = nowNanos
                true
            }
        }
    }
}
