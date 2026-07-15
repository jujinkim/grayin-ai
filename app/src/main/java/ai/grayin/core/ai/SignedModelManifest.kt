package ai.grayin.core.ai

import android.content.Context
import ai.grayin.core.artifact.FixedCatalogArtifactSpec
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SignedModelManifestEnvelope(
    val keyId: String,
    val payload: String,
    val signature: String,
)

@Serializable
data class ModelReleaseManifest(
    val schemaVersion: Int,
    val sequence: Long,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val minimumAppVersionCode: Int,
    val maximumAppVersionCode: Int? = null,
    val models: List<ModelReleaseManifestEntry>,
)

@Serializable
data class ModelReleaseManifestEntry(
    val modelId: String,
    val releaseVersion: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseUrl: String,
    val liteRtLmRuntimeVersion: String,
    val containerMajorVersion: Int,
    val deprecated: Boolean,
    val replacesVersion: String? = null,
) {
    internal fun artifactSpecOrNull(): FixedCatalogArtifactSpec? = runCatching {
        FixedCatalogArtifactSpec(
            id = "model-${sha256.take(16)}",
            url = downloadUrl,
            fileName = fileName,
            expectedSizeBytes = sizeBytes,
            sha256 = sha256,
        )
    }.getOrNull()
}

enum class ModelManifestRejectionCode {
    ENVELOPE_TOO_LARGE,
    ENVELOPE_INVALID,
    KEY_ID_INVALID,
    PUBLIC_KEY_INVALID,
    SIGNATURE_INVALID,
    PAYLOAD_TOO_LARGE,
    PAYLOAD_INVALID,
    SCHEMA_UNSUPPORTED,
    SEQUENCE_INVALID,
    TIME_WINDOW_INVALID,
    NOT_YET_VALID,
    EXPIRED,
    APP_VERSION_UNSUPPORTED,
    ENTRY_INVALID,
}

sealed interface ModelManifestVerificationResult {
    data class Verified(
        val manifest: ModelReleaseManifest,
        val payloadSha256: String,
    ) : ModelManifestVerificationResult

    data class Rejected(val code: ModelManifestRejectionCode) : ModelManifestVerificationResult
}

class SignedModelManifestVerifier(
    private val expectedKeyId: String,
    publicKeyX509: ByteArray,
    private val appVersionCode: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val publicKey: ECPublicKey? = parseP256PublicKey(publicKeyX509)

    fun verify(envelopeBytes: ByteArray): ModelManifestVerificationResult {
        if (envelopeBytes.isEmpty() || envelopeBytes.size > MAX_ENVELOPE_BYTES) {
            return rejected(ModelManifestRejectionCode.ENVELOPE_TOO_LARGE)
        }
        val envelope = SignedModelManifestCodec.decodeCanonicalEnvelope(envelopeBytes)
            ?: return rejected(ModelManifestRejectionCode.ENVELOPE_INVALID)
        if (envelope.keyId != expectedKeyId || !expectedKeyId.matches(SAFE_KEY_ID)) {
            return rejected(ModelManifestRejectionCode.KEY_ID_INVALID)
        }
        val key = publicKey ?: return rejected(ModelManifestRejectionCode.PUBLIC_KEY_INVALID)
        val payloadBytes = decodeBase64Url(envelope.payload, MAX_PAYLOAD_BYTES)
            ?: return rejected(ModelManifestRejectionCode.PAYLOAD_TOO_LARGE)
        val signatureBytes = decodeBase64Url(envelope.signature, MAX_SIGNATURE_BYTES)
            ?: return rejected(ModelManifestRejectionCode.SIGNATURE_INVALID)
        val signatureVerified = runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(payloadBytes)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
        if (!signatureVerified) return rejected(ModelManifestRejectionCode.SIGNATURE_INVALID)

        val manifest = SignedModelManifestCodec.decodeCanonicalPayload(payloadBytes)
            ?: return rejected(ModelManifestRejectionCode.PAYLOAD_INVALID)
        validateManifest(manifest)?.let { return rejected(it) }
        return ModelManifestVerificationResult.Verified(
            manifest = manifest,
            payloadSha256 = sha256(payloadBytes),
        )
    }

    private fun validateManifest(manifest: ModelReleaseManifest): ModelManifestRejectionCode? {
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return ModelManifestRejectionCode.SCHEMA_UNSUPPORTED
        }
        if (manifest.sequence <= 0L) return ModelManifestRejectionCode.SEQUENCE_INVALID
        if (
            manifest.issuedAtEpochSeconds <= 0L ||
            manifest.expiresAtEpochSeconds <= manifest.issuedAtEpochSeconds ||
            manifest.expiresAtEpochSeconds - manifest.issuedAtEpochSeconds > MAX_VALIDITY_SECONDS
        ) {
            return ModelManifestRejectionCode.TIME_WINDOW_INVALID
        }
        val now = clock.instant().epochSecond
        if (manifest.issuedAtEpochSeconds > now + MAX_CLOCK_SKEW_SECONDS) {
            return ModelManifestRejectionCode.NOT_YET_VALID
        }
        if (manifest.expiresAtEpochSeconds < now - MAX_CLOCK_SKEW_SECONDS) {
            return ModelManifestRejectionCode.EXPIRED
        }
        val maximumAppVersionCode = manifest.maximumAppVersionCode
        if (
            manifest.minimumAppVersionCode <= 0 ||
            (maximumAppVersionCode != null && maximumAppVersionCode < manifest.minimumAppVersionCode) ||
            appVersionCode < manifest.minimumAppVersionCode ||
            (maximumAppVersionCode != null && appVersionCode > maximumAppVersionCode)
        ) {
            return ModelManifestRejectionCode.APP_VERSION_UNSUPPORTED
        }
        if (
            manifest.models.isEmpty() ||
            manifest.models.size > MAX_MODEL_ENTRIES ||
            manifest.models.map { it.modelId }.toSet().size != manifest.models.size ||
            manifest.models.any { !validEntry(it) }
        ) {
            return ModelManifestRejectionCode.ENTRY_INVALID
        }
        return null
    }

    private fun validEntry(entry: ModelReleaseManifestEntry): Boolean {
        if (!entry.modelId.matches(SAFE_MODEL_ID)) return false
        if (!entry.releaseVersion.matches(SAFE_RELEASE_VERSION)) return false
        if (!entry.fileName.matches(SAFE_FILE_NAME) || !entry.fileName.endsWith(MODEL_EXTENSION)) return false
        if (!entry.sha256.matches(SHA_256) || entry.sizeBytes !in MIN_MODEL_BYTES..MAX_MODEL_BYTES) return false
        if (entry.liteRtLmRuntimeVersion != SUPPORTED_LITERT_LM_RUNTIME_VERSION) return false
        if (entry.containerMajorVersion != SUPPORTED_CONTAINER_MAJOR_VERSION) return false
        if (entry.replacesVersion != null && !entry.replacesVersion.matches(SAFE_RELEASE_VERSION)) return false
        if (entry.artifactSpecOrNull() == null) return false
        val licenseUri = runCatching { java.net.URI(entry.licenseUrl) }.getOrNull() ?: return false
        if (
            licenseUri.scheme != "https" ||
            licenseUri.host.isNullOrBlank() ||
            licenseUri.rawUserInfo != null ||
            licenseUri.port != -1 ||
            licenseUri.rawQuery != null ||
            licenseUri.rawFragment != null
        ) {
            return false
        }
        return true
    }

    private fun parseP256PublicKey(encoded: ByteArray): ECPublicKey? = runCatching {
        if (encoded.isEmpty() || encoded.size > MAX_PUBLIC_KEY_BYTES) return@runCatching null
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(encoded)) as? ECPublicKey
            ?: return@runCatching null
        key.takeIf { matchesP256(it.params) }
    }.getOrNull()

    private fun matchesP256(actual: ECParameterSpec): Boolean {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(P256_CURVE_NAME))
        }
        val expected = parameters.getParameterSpec(ECParameterSpec::class.java)
        return actual.curve == expected.curve &&
            actual.generator == expected.generator &&
            actual.order == expected.order &&
            actual.cofactor == expected.cofactor
    }

    private fun decodeBase64Url(value: String, maximumDecodedBytes: Int): ByteArray? {
        if (value.isEmpty() || !value.matches(BASE64_URL_NO_PADDING)) return null
        val maximumEncodedChars = ((maximumDecodedBytes + 2) / 3) * 4
        if (value.length > maximumEncodedChars) return null
        return runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= maximumDecodedBytes }
    }

    private fun sha256(value: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun rejected(code: ModelManifestRejectionCode) = ModelManifestVerificationResult.Rejected(code)

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val SUPPORTED_CONTAINER_MAJOR_VERSION = 1
        const val SUPPORTED_LITERT_LM_RUNTIME_VERSION = "0.13.1"
        const val MAX_ENVELOPE_BYTES = 64 * 1024
        const val MAX_PAYLOAD_BYTES = 48 * 1024
        const val MAX_MODEL_ENTRIES = 16
        const val MAX_VALIDITY_SECONDS = 31L * 24L * 60L * 60L
        const val MAX_CLOCK_SKEW_SECONDS = 5L * 60L
        const val MIN_MODEL_BYTES = 1024L * 1024L
        const val MAX_MODEL_BYTES = 8L * 1024L * 1024L * 1024L

        private const val MAX_SIGNATURE_BYTES = 128
        private const val MAX_PUBLIC_KEY_BYTES = 256
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val P256_CURVE_NAME = "secp256r1"
        private const val MODEL_EXTENSION = ".litertlm"
        private val SAFE_KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
        private val SAFE_MODEL_ID = Regex("[A-Za-z0-9._-]{1,80}")
        private val SAFE_RELEASE_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SHA_256 = Regex("[a-f0-9]{64}")
        private val BASE64_URL_NO_PADDING = Regex("[A-Za-z0-9_-]+")
    }
}

data class AcceptedModelManifestState(
    val sequence: Long,
    val payloadSha256: String,
)

enum class ModelManifestAcceptanceDecision {
    ACCEPT_NEW,
    ACCEPT_REPLAY,
    REJECT_ROLLBACK,
    REJECT_EQUIVOCATION,
    REJECT_STATE_INVALID,
    REJECT_PERSISTENCE_FAILURE,
}

object ModelManifestRollbackPolicy {
    fun decide(
        current: AcceptedModelManifestState?,
        candidate: ModelManifestVerificationResult.Verified,
    ): ModelManifestAcceptanceDecision {
        if (current == null || candidate.manifest.sequence > current.sequence) {
            return ModelManifestAcceptanceDecision.ACCEPT_NEW
        }
        if (candidate.manifest.sequence < current.sequence) {
            return ModelManifestAcceptanceDecision.REJECT_ROLLBACK
        }
        return if (candidate.payloadSha256 == current.payloadSha256) {
            ModelManifestAcceptanceDecision.ACCEPT_REPLAY
        } else {
            ModelManifestAcceptanceDecision.REJECT_EQUIVOCATION
        }
    }
}

sealed interface StoredModelManifestState {
    data object Empty : StoredModelManifestState

    data class Accepted(val value: AcceptedModelManifestState) : StoredModelManifestState

    data object Invalid : StoredModelManifestState
}

class ModelManifestStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readState(): StoredModelManifestState = synchronized(STATE_LOCK) {
        val hasSequence = prefs.contains(KEY_SEQUENCE)
        val hasDigest = prefs.contains(KEY_PAYLOAD_SHA256)
        if (!hasSequence && !hasDigest) return@synchronized StoredModelManifestState.Empty
        if (!hasSequence || !hasDigest) return@synchronized StoredModelManifestState.Invalid
        val sequence = prefs.getLong(KEY_SEQUENCE, -1L)
        val digest = prefs.getString(KEY_PAYLOAD_SHA256, null)
        if (sequence <= 0L || digest == null || !digest.matches(SHA_256)) {
            return@synchronized StoredModelManifestState.Invalid
        }
        StoredModelManifestState.Accepted(AcceptedModelManifestState(sequence, digest))
    }

    fun accept(candidate: ModelManifestVerificationResult.Verified): ModelManifestAcceptanceDecision {
        return synchronized(STATE_LOCK) {
            val current = when (val stored = readState()) {
                StoredModelManifestState.Empty -> null
                StoredModelManifestState.Invalid -> return@synchronized ModelManifestAcceptanceDecision.REJECT_STATE_INVALID
                is StoredModelManifestState.Accepted -> stored.value
            }
            val decision = ModelManifestRollbackPolicy.decide(current, candidate)
            if (decision != ModelManifestAcceptanceDecision.ACCEPT_NEW) return@synchronized decision
            val persisted = prefs.edit()
                .putLong(KEY_SEQUENCE, candidate.manifest.sequence)
                .putString(KEY_PAYLOAD_SHA256, candidate.payloadSha256)
                .commit()
            if (persisted) {
                ModelManifestAcceptanceDecision.ACCEPT_NEW
            } else {
                ModelManifestAcceptanceDecision.REJECT_PERSISTENCE_FAILURE
            }
        }
    }

    internal companion object {
        const val PREFS_NAME = "grayin_model_manifest_state"
        const val KEY_SEQUENCE = "accepted_sequence"
        const val KEY_PAYLOAD_SHA256 = "accepted_payload_sha256"
        private val SHA_256 = Regex("[a-f0-9]{64}")
        private val STATE_LOCK = Any()
    }
}

internal object SignedModelManifestCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false
    }

    fun canonicalPayloadBytes(manifest: ModelReleaseManifest): ByteArray {
        return json.encodeToString(manifest).encodeToByteArray()
    }

    fun canonicalEnvelopeBytes(envelope: SignedModelManifestEnvelope): ByteArray {
        return json.encodeToString(envelope).encodeToByteArray()
    }

    fun decodeCanonicalPayload(bytes: ByteArray): ModelReleaseManifest? = runCatching {
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val decoded = json.decodeFromString<ModelReleaseManifest>(text)
        decoded.takeIf { canonicalPayloadBytes(it).contentEquals(bytes) }
    }.getOrNull()

    fun decodeCanonicalEnvelope(bytes: ByteArray): SignedModelManifestEnvelope? = runCatching {
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val decoded = json.decodeFromString<SignedModelManifestEnvelope>(text)
        decoded.takeIf { canonicalEnvelopeBytes(it).contentEquals(bytes) }
    }.getOrNull()
}

/** Production activation remains fail-closed until release operations provide both values. */
object RemoteModelManifestConfiguration {
    val endpointUrl: String? = null
    val publicKeyX509Base64: String? = null

    val configured: Boolean
        get() = endpointUrl != null && publicKeyX509Base64 != null
}
