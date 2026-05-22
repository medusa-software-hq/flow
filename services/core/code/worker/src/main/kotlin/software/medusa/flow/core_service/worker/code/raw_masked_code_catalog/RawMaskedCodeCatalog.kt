package software.medusa.flow.core_service.worker.code.raw_masked_code_catalog

import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.tc.ControlChar

data class RawMaskedCodeCatalog(
    val files: List<RawMaskedCodeFile>,
) {
  fun encodeToString(): String = buildString {
    append(ControlChar.SOH)
    append(keyword)
    append(ControlChar.STX)
    append(
        files.joinToString(separator = ControlChar.FS.toString()) { file -> file.encodeToString() }
    )
    append(ControlChar.ETX)
  }

  companion object {
    const val keyword = "INPUT"

    val repoPseudoRootPath: LiteralAbsoluteUnixPath =
        AbsoluteUnixPath.of(
            UnixPath.Name.Literal("repo"),
        )

    val ebnfGrammarDescription =
        """
        (* a masked code catalog for AI patch generation *)
        catalog = "${ControlChar.SOH}", "$keyword", "${ControlChar.STX}", input_file, { "${ControlChar.FS}", input_file }, "${ControlChar.ETX}" ;

        (* source file with original line records *)
        input_file = "${ControlChar.SOH}", file_path, "${ControlChar.STX}", [ line_record, { "${ControlChar.RS}", line_record } ], "${ControlChar.ETX}" ;

        line_record = literal_line_record | masked_line_record ;
        literal_line_record = line_number, "${ControlChar.US}", line_content ;
        masked_line_record = "MASKED", "${ControlChar.US}", mask_reason ;

        (* 1-based line number *)
        line_number = nonzero_digit, { digit } ;

        digit = "0" | nonzero_digit ;
        nonzero_digit = "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;

        (* absolute Unix-style file path *)
        file_path = text ;
        line_content = text ;
        mask_reason = text ;

        (* text string, possibly empty *)
        text = { text_char } ;
        text_char = ? any non-control character ? ;
      """
            .trimIndent()
  }
}

data class RawMaskedCodeFile(
    val filePath: LiteralAbsoluteUnixPath,
    val records: List<RawMaskedCodeRecord>,
) {
  fun encodeToString(): String = buildString {
    append(ControlChar.SOH)
    append(filePath.toUnixAbsolutePathString())
    append(ControlChar.STX)
    append(
        records.joinToString(separator = ControlChar.RS.toString()) { record ->
          record.encodeToString()
        }
    )
    append(ControlChar.ETX)
  }
}

sealed class RawMaskedCodeRecord {
  abstract fun encodeToString(): String
}

data class RawLiteralLineRecord(
    val lineIndex: CodeBlock.LineIndex,
    val lineContent: CodeBlock.Line,
) : RawMaskedCodeRecord() {
  override fun encodeToString(): String = buildString {
    append(lineIndex.indexOneBased)
    append(ControlChar.US)
    append(lineContent.content)
  }
}

data class RawMaskedLineRecord(
    val maskReason: String,
) : RawMaskedCodeRecord() {
  init {
    require(maskReason.none { ControlChar.isControl(it) }) {
      "Mask reason cannot contain control characters"
    }
  }

  override fun encodeToString(): String = buildString {
    append("MASKED")
    append(ControlChar.US)
    append(maskReason)
  }
}
