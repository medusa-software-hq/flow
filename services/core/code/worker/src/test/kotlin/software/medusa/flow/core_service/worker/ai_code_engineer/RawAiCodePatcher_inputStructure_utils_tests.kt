package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.ai_code_engineer.RawAiCodePatcher_inputStructure_utils.toRawMaskedCodeCatalog
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndex
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.flow.core_service.worker.code.CodeFileContent
import software.medusa.flow.core_service.worker.code.raw_masked_code_catalog.RawMaskedCodeCatalog
import software.medusa.flow.core_service.worker.code.tc.ControlChar

class RawAiCodePatcher_inputStructure_utils_tests {
  @Test
  fun test_toRawMaskedCodeCatalog_encodesRepoPseudoPathsAndMaskedLines() {
    val filePath =
        RelativeUnixPath.of(
            UnixPath.Name.Literal("config"),
            UnixPath.Name.Literal("module.yaml"),
        )

    val maskedCodeCatalog =
        MaskedCodeCatalog(
            maskedCodeFileContentByPath =
                mapOf(
                    filePath to
                        MaskedCodeFileContent(
                            codeFileContent =
                                CodeFileContent.of(
                                    "alpha: 1",
                                    "beta: 2",
                                ),
                            mask =
                                MaskedCodeFileContent.Mask(
                                    maskedLineRanges =
                                        setOf(
                                            LineIndexRange.of(
                                                startIndex = LineIndex.ofOneBased(2),
                                                length = 1,
                                            ),
                                        ),
                                ),
                        ),
                ),
        )

    val encoded = maskedCodeCatalog.toRawMaskedCodeCatalog().encodeToString()

    assertEquals(
        expected =
            buildString {
              append(ControlChar.SOH)
              append(RawMaskedCodeCatalog.keyword)
              append(ControlChar.STX)
              append(ControlChar.SOH)
              append("/repo/config/module.yaml")
              append(ControlChar.STX)
              append("1")
              append(ControlChar.US)
              append("alpha: 1")
              append(ControlChar.RS)
              append("MASKED")
              append(ControlChar.US)
              append("Masked line")
              append(ControlChar.ETX)
              append(ControlChar.ETX)
            },
        actual = encoded,
    )
  }
}
