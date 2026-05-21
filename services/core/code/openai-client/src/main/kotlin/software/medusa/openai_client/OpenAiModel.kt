package software.medusa.openai_client

import com.aallam.openai.api.model.ModelId
import kotlinx.serialization.Serializable

/** OpenAI (or OpenAI-compatible) model. */
@Serializable
sealed class OpenAiModel {
  @Serializable
  data object GptMidi : OpenAiModel() {
    override val id: String = "gpt-5.4"
  }

  @Serializable
  data object GptOss120b : OpenAiModel() {
    override val id: String = "openai/gpt-oss-120b"
  }

  @Serializable
  data object Gemma4B : OpenAiModel() {
    override val id: String = "google/gemma-3-4b-it"
  }

  @Serializable
  data object Gemma12B : OpenAiModel() {
    override val id: String = "google/gemma-3-12b-it"
  }

  @Serializable
  data object Gemma31B : OpenAiModel() {
    override val id: String = "google/gemma-4-31b-it"
  }

  // 2x cheaper than 3.1 Flash Lite
  @Serializable
  data object GeminiFlashLitePrevious : OpenAiModel() {
    override val id: String = "google/gemini-2.5-flash-lite"
  }

  @Serializable
  data object GeminiFlashLite : OpenAiModel() {
    override val id: String = "google/gemini-3.1-flash-lite"
  }

  // 5x cheaper on input than 3.5 Flash
  @Serializable
  data object GeminiFlashPrevious : OpenAiModel() {
    override val id: String = "google/gemini-2.5-flash"
  }

  @Serializable
  data object GeminiFlash : OpenAiModel() {
    override val id: String = "google/gemini-3.5-flash"
  }

  @Serializable
  data object DeepSeekFlash : OpenAiModel() {
    override val id: String = "deepseek/deepseek-v4-flash"
  }

  @Serializable
  data object DeepSeekPro : OpenAiModel() {
    override val id: String = "deepseek/deepseek-v4-pro"
  }

  @Serializable
  data object MiniMax : OpenAiModel() {
    override val id: String = "minimax/minimax-m2.7"
  }

  internal fun toSdkModelId(): ModelId =
      ModelId(
          id = id,
      )

  abstract val id: String
}
