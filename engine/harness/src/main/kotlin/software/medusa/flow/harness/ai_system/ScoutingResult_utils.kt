package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutRequest
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingResult
import software.medusa.flow.harness.ai_system.MdInlineContent_utils.asPlainText
import software.medusa.flow.harness.ai_system.ScoutRequest_utils.dump
import software.medusa.flow.harness.ai_system.ScoutRequest_utils.load

/**
 * Codec between a [ScoutingResult] and its top-level Markdown document.
 *
 * The document's heading decides the outcome: `# CONTINUE` carries a [ScoutRequest] as its body,
 * while `# STOP` (with no body) signals that scouting is finished.
 */
internal data object ScoutingResult_utils {
  private const val continueHeading = "CONTINUE"

  private const val stopHeading = "STOP"

  fun ScoutingResult.dump(): MdDocument =
      when (this) {
        is ScoutingResult.Continued ->
            MdDocument(
                rootChapter =
                    MdChapter.leaf(
                        title = MdInlineContent.of(continueHeading),
                        element = scoutRequest.dump(),
                    ),
            )

        ScoutingResult.Completed ->
            MdDocument(
                rootChapter =
                    MdChapter.leaf(
                        title = MdInlineContent.of(stopHeading),
                        element = MdElement.Empty,
                    ),
            )
      }

  fun ScoutingResult.Companion.load(
      document: MdDocument,
  ): ScoutingResult {
    val heading = document.rootChapter.title.asPlainText()

    return when (heading) {
      continueHeading ->
          ScoutingResult.Continued(
              scoutRequest = ScoutRequest.load(element = document.rootChapter.element),
          )

      stopHeading -> ScoutingResult.Completed

      else -> error("Unrecognized scouting-result heading: `$heading`")
    }
  }
}
