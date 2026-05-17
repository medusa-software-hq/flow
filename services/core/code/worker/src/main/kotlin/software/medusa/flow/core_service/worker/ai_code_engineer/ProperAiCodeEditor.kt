package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlinx.schema.Description
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.EditionScope
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.Patch
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.PatchSet
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodeEditor.StructuredResponse.StructuredPatch
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndex
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiMessage
import software.medusa.openai_client.OpenAiModel
import software.medusa.openai_client.OpenAiRole
import software.medusa.openai_client.createStructuredCompletion

private object ControlCharacters {
  const val StartOfText = '␂'
  const val EndOfText = '␃'
  const val Enquiry = '␅'
  const val Bell = '␇'
  const val GroupSeparator = '␝'
  const val RecordSeparator = '␞'
  const val EndOfTransmission = '␗'
}

private object SpecialCharacters {
  const val CircledOne = '①'
  const val CircledTwo = '②'
}

private val systemPrompt =
    """
  Edit the input files in accordance with the provided instructions.
  Parts of the files might be masked. These parts shouldn't be edited.
  
  The input files will be encoded in a custom format based on ASCII control characters:
  
  ${ControlCharacters.Enquiry}
  <file path 1>
  ${ControlCharacters.StartOfText}
  1${ControlCharacters.RecordSeparator}<line 1>
  2${ControlCharacters.RecordSeparator}<line 2>
  <other lines...>
  ${ControlCharacters.Bell} <summary of masked part>
  ${ControlCharacters.Bell} <summary of masked part, cont.>
  <line number N>${ControlCharacters.RecordSeparator}<line N>
  <line number N+1>${ControlCharacters.RecordSeparator}<line N+1>
  <other lines...>
  ${ControlCharacters.EndOfText}
  ${ControlCharacters.GroupSeparator}
  <file path 2>
  ${ControlCharacters.StartOfText}
  <file 2 content...>
  ${ControlCharacters.EndOfText}
  <other files...>
  ${ControlCharacters.EndOfTransmission}
"""
        .trimIndent()

class ProperAiCodeEditor(
    private val openAiClient: OpenAiClient,
) : AiCodeEditor {
  @Serializable
  @SerialName("Response")
  private data class StructuredResponse(
      @Description(
          "Patches to apply to the input files. A given file can be patched only by one patch."
      )
      val patches: List<StructuredPatch>,
  ) {
    @Serializable
    @SerialName("Patch")
    @Description("A patch to be applied to a file.")
    data class StructuredPatch(
        @Description("Path of the file to patch (as in the input).") val filePath: String,
        @Description("List of patch fragments to apply to the file.")
        val fragments: List<StructuredPatchFragment>,
    )

    @Serializable
    @SerialName("PatchFragment")
    @Description(
        "A patch to be applied to a file. Indices refer to the original content. Lines can be removed by mapping them to an empty string. New lines can be added by mapping a zero-line range to a non-empty string."
    )
    data class StructuredPatchFragment(
        @Description("One-based index of the first line to patch (inclusive).") val start: Int,
        @Description("One-based index of the last line to patch (exclusive).")
        val endExclusive: Int,
        @Description(
            "Content to replace the lines with. Lines should be LF-terminated. An empty string has a special meaning."
        )
        val content: String,
    )
  }

  companion object {
    private fun StructuredResponse.toPatchSet(): PatchSet =
        PatchSet(
            patchByFilePath =
                patches.associate { patch ->
                  val filePath =
                      RelativeUnixPath.parse(patch.filePath).toLiteral()
                          ?: throw IllegalArgumentException(
                              "Patch path must consist of literal path segments: ${patch.filePath}",
                          )

                  filePath to patch.toPatch()
                },
        )

    private fun StructuredPatch.toPatch(): Patch =
        Patch(
            fragmentByOldLineIndexRange =
                fragments.associate { patch ->
                  LineIndexRange(
                      startIndex =
                          LineIndex.ofOneBased(
                              indexOneBased = patch.start,
                          ),
                      endIndexExclusive =
                           LineIndex.ofOneBased(
                               indexOneBased = patch.endExclusive,
                           ),
                  ) to
                      Patch.Fragment(
                          CodeBlock.parse(
                              rawContent = patch.content,
                          ),
                      )
                },
        )
  }

  override suspend fun generateEditionPatchSet(
      editionInstructions: AiCodeEditor.EditionInstructions,
      editionScope: EditionScope,
  ): PatchSet {
    val userPromptBlock =
        CodeBlock.concat(
            CodeBlock.of(
                "${SpecialCharacters.CircledOne} Instructions",
                "",
            ),
            editionInstructions.instructions,
            CodeBlock.of(
                "",
                "${SpecialCharacters.CircledTwo} Encoded input files",
                "",
            ),
            editionScope.toEncodedBlock(),
        )

    val userPrompt = userPromptBlock.dump()

    val completionInput =
        OpenAiChat(
            messages =
                listOf(
                    OpenAiMessage(
                        role = OpenAiRole.System,
                        text = systemPrompt,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.User,
                        text = userPrompt,
                    ),
                ),
        )

    val completionResponse =
        openAiClient.createStructuredCompletion(
            request =
                OpenAiClient.CompletionRequest(
                    input = completionInput,
                    model = OpenAiModel.GptMidi,
                ),
            responseSerializer = StructuredResponse.serializer(),
        )

    val structuredResponse = completionResponse.responseObject

    return structuredResponse.toPatchSet()
  }
}

private fun EditionScope.toEncodedBlock(): CodeBlock =
    CodeBlock.concat(
        CodeBlock.of(
            "${ControlCharacters.Enquiry}",
        ),
        CodeBlock.joinBy(
            blocks =
                maskedCodeFileContentByPath.map { (filePath, maskedContent) ->
                  maskedContent.toEncodedEntryBlock(filePath = filePath)
                },
            separator =
                CodeBlock.of(
                    "${ControlCharacters.GroupSeparator}",
                ),
        ),
        CodeBlock.of(
            "${ControlCharacters.EndOfTransmission}",
        ),
    )

private fun MaskedCodeFileContent.toEncodedEntryBlock(
    filePath: LiteralRelativeUnixPath,
): CodeBlock =
    CodeBlock.concat(
        CodeBlock.of(
            filePath.toUnixRelativePathString(),
            "${ControlCharacters.StartOfText}",
        ),
        toEncodedBlock(),
        CodeBlock.of(
            "${ControlCharacters.EndOfText}",
        ),
    )

private fun MaskedCodeFileContent.toEncodedBlock(): CodeBlock =
    CodeBlock.concat(
        blocks = blocks.map { it.toEncodedBlock() },
    )

private fun MaskedCodeFileContent.Block.toEncodedBlock(): CodeBlock =
    when (this) {
      is MaskedCodeFileContent.ContentBlock -> toContentEncodedBlock()
      is MaskedCodeFileContent.MaskBlock -> toMaskEncodedBlock()
    }

private fun MaskedCodeFileContent.ContentBlock.toContentEncodedBlock(): CodeBlock =
    CodeBlock.of(
        lines =
            indexedLines
                .map { (lineIndex, line) ->
                  "${lineIndex.indexOneBased}${ControlCharacters.RecordSeparator}${line.content}"
                }
                .toList(),
    )

private fun MaskedCodeFileContent.MaskBlock.toMaskEncodedBlock(): CodeBlock =
    CodeBlock.of(
        lines = summary.lines.map { line -> "${ControlCharacters.Bell}${line.content}" }.toList(),
    )
