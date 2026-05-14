package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test

class ProperAiCodeEngineer_tests {

  @Test
  fun test_foo() {

    val aiCodeEditor =
        object : AiCodeEditor {
          override suspend fun generateEditionPatchSet(
              editionInstructions: AiCodeEditor.EditionInstructions,
              editionScope: AiCodeEditor.EditionScope,
          ): AiCodeEditor.PatchSet {
            TODO("Not yet implemented")
          }
        }

    val aiCodeEngineer =
        ProperAiCodeEngineer(
            aiCodeEditor = aiCodeEditor,
        )
  }
}
