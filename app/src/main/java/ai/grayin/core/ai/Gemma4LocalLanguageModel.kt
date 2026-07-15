package ai.grayin.core.ai

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Gemma4LocalLanguageModel(
    context: Context,
    private val modelPathResolver: Gemma4ModelPathResolver = Gemma4ModelPathResolver(context.applicationContext),
) : LocalLanguageModel {
    override val metadata: LocalModelMetadata = LocalModelMetadata(
        modelId = "gemma-4-E2B-it-litertlm",
        displayName = "Gemma 4 E2B Local",
        localOnly = true,
        commercialApi = false,
        networkRequired = false,
    )

    override suspend fun status(): LocalModelStatus {
        return if (modelPathResolver.resolveModelPath() == null) {
            LocalModelStatus.UNAVAILABLE
        } else {
            LocalModelStatus.READY
        }
    }

    override suspend fun generate(evidencePack: EvidencePack): LocalModelAnswerDraft {
        val modelPath = modelPathResolver.resolveModelPath()
            ?: return unavailableDraft(evidencePack)
        val prompt = EvidencePackPromptBuilder.build(evidencePack)
        val answer = generateResponse(modelPath, prompt)
        return LocalModelAnswerDraft(
            answer = answer.ifBlank { "Local Gemma returned an empty answer." },
            usedEvidenceItemIds = LocalModelGrounding.evidenceIdsFromAnswer(answer),
            inferenceNotes = listOf("Generated locally by Gemma from derived EvidencePack only."),
            confidence = combineConfidence(evidencePack.evidenceItems, evidencePack.missingSources.isNotEmpty()),
            missingSources = evidencePack.missingSources,
        )
    }

    @OptIn(ExperimentalApi::class)
    private fun generateResponse(modelPath: String, prompt: String): String {
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = modelPathResolver.cacheDirPath(),
            ),
        )
        try {
            engine.initialize()
            val conversation = engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = TOP_K,
                        topP = TOP_P,
                        temperature = TEMPERATURE,
                        seed = SEED,
                    ),
                    automaticToolCalling = false,
                ),
            )
            try {
                val message = conversation.sendMessage(prompt)
                return conversation.renderMessageIntoString(message).trim()
            } finally {
                conversation.close()
            }
        } finally {
            engine.close()
        }
    }

    private fun unavailableDraft(evidencePack: EvidencePack): LocalModelAnswerDraft {
        return LocalModelAnswerDraft(
            answer = "Local Gemma 4 E2B model file is not installed.",
            usedEvidenceItemIds = emptyList(),
            inferenceNotes = listOf("No model inference ran."),
            confidence = ConfidenceLevel.UNKNOWN,
            missingSources = evidencePack.missingSources,
        )
    }

    private fun combineConfidence(evidence: List<EvidenceItem>, hasMissingSources: Boolean): ConfidenceLevel {
        if (evidence.isEmpty()) return ConfidenceLevel.UNKNOWN
        if (evidence.any { it.confidence == ConfidenceLevel.LOW }) return ConfidenceLevel.LOW
        if (hasMissingSources) return ConfidenceLevel.MEDIUM
        if (evidence.all { it.confidence == ConfidenceLevel.HIGH }) return ConfidenceLevel.HIGH
        if (evidence.any { it.confidence == ConfidenceLevel.MEDIUM }) return ConfidenceLevel.MEDIUM
        return ConfidenceLevel.UNKNOWN
    }

    private companion object {
        const val MAX_NUM_TOKENS = 4096
        const val TOP_K = 40
        const val TOP_P = 0.95
        const val TEMPERATURE = 0.2
        const val SEED = 1
    }
}

class Gemma4ModelPathResolver(
    private val context: Context,
    private val modelInstallStore: ModelInstallStore = ModelInstallStore(context.applicationContext),
) {
    fun resolveModelPath(): String? {
        return LiteRtLmReadyPathResolver.firstCompatibleFile(modelCandidates())?.absolutePath
    }

    fun modelCandidates(): List<File> {
        val externalFilesDir = context.getExternalFilesDir(null)
        val selectedDownloadedModel = modelInstallStore.selectedReadyModelFile()
        return listOfNotNull(
            selectedDownloadedModel,
            File(context.filesDir, INTERNAL_MODEL_PATH),
            externalFilesDir?.let { File(it, EXTERNAL_MODEL_PATH) },
            File(ADB_MODEL_PATH),
        )
    }

    suspend fun installModelFromUri(uri: Uri): Long = IMPORT_MUTEX.withLock {
        val metadata = documentMetadata(uri)
        require(metadata.displayName == null || metadata.displayName.endsWith(MODEL_EXTENSION, ignoreCase = true)) {
            "Selected model file must use $MODEL_EXTENSION extension."
        }

        val destination = File(context.filesDir, INTERNAL_MODEL_PATH)
        val modelDir = requireNotNull(destination.parentFile) { "Model directory is unavailable." }
        if (!modelDir.isDirectory && !modelDir.mkdirs()) {
            throw IOException("Model directory cannot be created.")
        }
        val stagingFile = File(modelDir, STAGING_MODEL_FILE_NAME)
        if (stagingFile.exists() && !stagingFile.delete()) {
            throw IOException("Stale model staging file cannot be removed.")
        }

        val cancellationSignal = CancellationSignal()
        val job = currentCoroutineContext()[Job]
        val cancellationHandle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) cancellationSignal.cancel()
        }
        var descriptorOwnershipTransferred = false
        try {
            job?.ensureActive()
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r", cancellationSignal)
                ?: throw IOException("Selected model file cannot be opened.")
            try {
                val descriptorSize = descriptor.statSize.takeIf { size -> size >= 0L }
                val sourceSize = resolveSourceSize(descriptorSize, metadata.sizeBytes)
                LiteRtLmContainerPolicy.requireSupportedSourceSize(sourceSize)
                val source = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                descriptorOwnershipTransferred = true
                LocalModelImportTransaction().import(
                    source = source,
                    expectedBytes = sourceSize,
                    usableSpaceBytes = modelDir.usableSpace,
                    stagingFile = stagingFile,
                    destinationFile = destination,
                    cancellationCheck = { job?.ensureActive() },
                )
            } finally {
                if (!descriptorOwnershipTransferred) descriptor.close()
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    suspend fun deleteImportedModel(): Boolean = IMPORT_MUTEX.withLock {
        managedModelFiles().fold(false) { deletedAny, file ->
            if (file.exists()) file.delete() || deletedAny else deletedAny
        }
    }

    fun cacheDirPath(): String {
        return File(context.cacheDir, CACHE_DIR).apply { mkdirs() }.absolutePath
    }

    private fun managedModelFiles(): List<File> {
        val externalFilesDir = context.getExternalFilesDir(null)
        return listOfNotNull(
            File(context.filesDir, INTERNAL_MODEL_PATH),
            File(context.filesDir, "models/$STAGING_MODEL_FILE_NAME"),
            externalFilesDir?.let { File(it, EXTERNAL_MODEL_PATH) },
        )
    }

    private fun documentMetadata(uri: Uri): ModelDocumentMetadata {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use ModelDocumentMetadata()
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    ModelDocumentMetadata(
                        displayName = if (nameIndex < 0 || cursor.isNull(nameIndex)) null else cursor.getString(nameIndex),
                        sizeBytes = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) {
                            null
                        } else {
                            cursor.getLong(sizeIndex).takeIf { size -> size >= 0L }
                        },
                    )
                }
        }.getOrNull() ?: ModelDocumentMetadata()
    }

    private fun resolveSourceSize(descriptorSize: Long?, metadataSize: Long?): Long {
        require(descriptorSize != null || metadataSize != null) {
            "Selected model provider did not expose a bounded file size."
        }
        require(descriptorSize == null || metadataSize == null || descriptorSize == metadataSize) {
            "Selected model provider reported inconsistent file sizes."
        }
        return requireNotNull(descriptorSize ?: metadataSize)
    }

    private data class ModelDocumentMetadata(
        val displayName: String? = null,
        val sizeBytes: Long? = null,
    )

    private companion object {
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_EXTENSION = ".litertlm"
        const val INTERNAL_MODEL_PATH = "models/$MODEL_FILE_NAME"
        const val EXTERNAL_MODEL_PATH = "models/$MODEL_FILE_NAME"
        const val ADB_MODEL_PATH = "/data/local/tmp/grayin/$MODEL_FILE_NAME"
        const val CACHE_DIR = "litertlm"
        const val STAGING_MODEL_FILE_NAME = "$MODEL_FILE_NAME.importing"
        val IMPORT_MUTEX = Mutex()
    }
}
