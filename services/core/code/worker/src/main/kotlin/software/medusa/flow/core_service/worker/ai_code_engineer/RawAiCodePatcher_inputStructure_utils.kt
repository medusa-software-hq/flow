package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.code.CodeBlock.LineIndexRange
import software.medusa.commons.paths.resolve
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.code.raw_masked_code_catalog.RawLiteralLineRecord
import software.medusa.flow.core_service.worker.code.raw_masked_code_catalog.RawMaskedCodeCatalog
import software.medusa.flow.core_service.worker.code.raw_masked_code_catalog.RawMaskedCodeFile
import software.medusa.flow.core_service.worker.code.raw_masked_code_catalog.RawMaskedCodeRecord
import software.medusa.flow.core_service.worker.code.raw_masked_code_catalog.RawMaskedLineRecord

internal data object RawAiCodePatcher_inputStructure_utils {
  val maskedCodeCatalogGrammar: String = RawMaskedCodeCatalog.ebnfGrammarDescription

  fun MaskedCodeCatalog.toRawMaskedCodeCatalog(): RawMaskedCodeCatalog =
      RawMaskedCodeCatalog(
          files =
              maskedCodeFileContentByPath.entries
                  .sortedBy { (filePath, _) -> filePath.toUnixRelativePathString() }
                  .map { (filePath, maskedCodeFileContent) ->
                    RawMaskedCodeFile(
                        filePath = RawMaskedCodeCatalog.repoPseudoRootPath.resolve(filePath),
                        records = maskedCodeFileContent.toRawMaskedCodeRecords(),
                    )
                  },
      )

  private fun MaskedCodeFileContent.toRawMaskedCodeRecords(): List<RawMaskedCodeRecord> =
      codeFileContent.indexedLines
          .map { indexedLine ->
            val lineRange = LineIndexRange.of(startIndex = indexedLine.index, length = 1)

            if (mask.maskedLineRanges.any { it.overlaps(lineRange) }) {
              RawMaskedLineRecord(
                  maskReason = "Masked line",
              )
            } else {
              RawLiteralLineRecord(
                  lineIndex = indexedLine.index,
                  lineContent = indexedLine.line,
              )
            }
          }
          .toList()
}
