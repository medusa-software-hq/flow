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
  data object Gemma4B : OpenAiModel() {
    override val id: String = "google/gemma-3-4b-it"
  }

  internal fun toSdkModelId(): ModelId =
      ModelId(
          id = id,
      )

  abstract val id: String
}
