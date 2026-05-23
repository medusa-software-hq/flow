package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.code.CodeBlock.LineIndexRange
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.code.tc.ControlChar
import software.medusa.flow.core_service.worker.code.tc.TcFile
import software.medusa.flow.core_service.worker.code.tc.TcGroup
import software.medusa.flow.core_service.worker.code.tc.TcMessage
import software.medusa.flow.core_service.worker.code.tc.TcRecord
import software.medusa.flow.core_service.worker.code.tc.TcString
import software.medusa.flow.core_service.worker.code.tc.TcUnit

internal data object ProperAiCodePatcher_inputStructure_utils {
  val maskedCodeCatalogTcGrammar: String =
      """
      Masked code catalog EBNF grammar:
    
      body = "${ControlChar.SOH}" "${ControlChar.STX}" input_file ("${ControlChar.FS}" input_file )* "${ControlChar.ETX} "
      input_file = file_path "${ControlChar.GS}" file_content
      file_content = (line_record ("${ControlChar.RS}" line_record )*)?
      line_record = literal_line_record | masked_line_record 
      literal_line_record = line_number "${ControlChar.US}" line_content
      masked_line_record = "MASKED" "${ControlChar.US}" mask_reason
    """
          .trimIndent()

  fun MaskedCodeCatalog.encodeToTcMessage(): TcMessage =
      TcMessage(
          header = TcString(""),
          files =
              maskedCodeFileContentByPath.entries
                  .sortedBy { (filePath, _) -> filePath.toUnixRelativePathString() }
                  .map { (filePath, maskedCodeFileContent) ->
                    maskedCodeFileContent.encodeToTcFile(filePath = filePath)
                  },
      )

  fun MaskedCodeFileContent.encodeToTcFile(
      filePath: LiteralRelativeUnixPath,
  ): TcFile =
      TcFile(
          groups =
              listOf(
                  TcGroup.of(
                      value = TcString(filePath.toUnixRelativePathString()),
                  ),
                  TcGroup(
                      records = encodeMaskedContentToTcRecords(),
                  ),
              ),
      )

  fun MaskedCodeFileContent.encodeMaskedContentToTcRecords(): List<TcRecord> =
      codeFileContent.indexedLines
          .map { indexedLine ->
            val lineRange = LineIndexRange.of(startIndex = indexedLine.index, length = 1)

            if (mask.maskedLineRanges.any { it.overlaps(lineRange) }) {
              TcRecord(
                  units =
                      listOf(
                          TcUnit.of(value = TcString("MASKED")),
                          TcUnit.of(value = TcString("Masked line")),
                      ),
              )
            } else {
              TcRecord(
                  units =
                       listOf(
                           TcUnit.of(value = TcString(indexedLine.index.indexOneBased.toString())),
                           TcUnit.of(value = TcString(indexedLine.line.content)),
                       ),
               )
             }
          }
          .toList()
}
