package software.medusa.openai_client

import com.aallam.openai.api.model.ModelId

/** OpenAI (or OpenAI-compatible) model. */
sealed class OpenAiModel {
  data object GptMidi : OpenAiModel() {
    override val id: String = "gpt-5.4"
  }

  data object Gemma4B : OpenAiModel() {
    override val id: String = "google/gemma-3-4b-it"
  }

  internal fun toSdkModelId(): ModelId =
      ModelId(
          id = id,
      )

  abstract val id: String
}
