package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlinx.schema.Description
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.Patch
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.PatchSet
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndex
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.flow.core_service.worker.code_project.CodeTool

internal data object ProperAiCodePatcher_responseStructure_utils {
  @Serializable
  @SerialName("Response")
  data class StructuredResponse(
      @Description(
          "Patches to apply to the input files. A given file can be patched only by one patch.",
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
    ) {
      fun toPatch(): Patch =
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

    @Serializable
    @SerialName("PatchFragment")
    @Description(
        "A patch to be applied to a file. Indices refer to the original content. Lines can be removed by mapping them to an empty string. New lines can be added by mapping a zero-line range to a non-empty string.",
    )
    data class StructuredPatchFragment(
        @Description("One-based index of the first line to patch (inclusive).") val start: Int,
        @Description("One-based index of the last line to patch (exclusive).")
        val endExclusive: Int,
        @Description(
            "Content to replace the lines with. Lines should be LF-terminated. An empty string has a special meaning.",
        )
        val content: String,
    )

    fun toPatchSet(): PatchSet =
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
  }

  fun buildIssueFixingPrompt(
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
  ): String = buildString {
    appendLine("Original task description:")
    appendLine(originalTaskDescription)
    appendLine()
    appendLine("Module diagnosis:")

    moduleDiagnosis.diagnosisByFilePath.forEach { (filePath, diagnosis) ->
      appendLine(filePath.toUnixRelativePathString())

      diagnosis.issues.forEach { issue -> appendLine("- ${issue.description}") }
    }
  }
}
