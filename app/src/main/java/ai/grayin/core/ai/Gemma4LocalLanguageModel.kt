package ai.grayin.core.ai

import android.content.Context
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
            usedEvidenceItemIds = evidencePack.evidenceItems.map { it.id },
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
) {
    fun resolveModelPath(): String? {
        return modelCandidates()
            .firstOrNull { file -> file.isFile && file.canRead() }
            ?.absolutePath
    }

    fun modelCandidates(): List<File> {
        val externalFilesDir = context.getExternalFilesDir(null)
        return listOfNotNull(
            File(context.filesDir, INTERNAL_MODEL_PATH),
            externalFilesDir?.let { File(it, EXTERNAL_MODEL_PATH) },
            File(ADB_MODEL_PATH),
        )
    }

    fun cacheDirPath(): String {
        return File(context.cacheDir, CACHE_DIR).apply { mkdirs() }.absolutePath
    }

    private companion object {
        const val INTERNAL_MODEL_PATH = "models/gemma-4-E2B-it.litertlm"
        const val EXTERNAL_MODEL_PATH = "models/gemma-4-E2B-it.litertlm"
        const val ADB_MODEL_PATH = "/data/local/tmp/grayin/gemma-4-E2B-it.litertlm"
        const val CACHE_DIR = "litertlm"
    }
}
