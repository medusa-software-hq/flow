package software.medusa.openai_client

import com.aallam.openai.api.model.ModelId

sealed class OpenAiModel {
  data object GptMidi : OpenAiModel() {
    override val id: String = "gpt-5.4"
  }

  internal fun toSdkModelId(): ModelId =
      ModelId(
          id = id,
      )

  abstract val id: String
}
