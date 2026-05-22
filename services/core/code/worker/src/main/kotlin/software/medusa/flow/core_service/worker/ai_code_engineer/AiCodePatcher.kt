package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.CodeMasker
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.flow.core_service.worker.code.CodeFileContent
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool.CodeModuleDiagnosis

interface AiCodePatcher {
  data class MaskedCodeCatalog(
      val maskedCodeFileContentByPath: Map<LiteralRelativeUnixPath, MaskedCodeFileContent>,
  )

  data class MaskedCodeFileContent(
      val codeFileContent: CodeFileContent,
      val mask: Mask,
  ) {
    data class Mask(
        val maskedLineRanges: Set<LineIndexRange>,
    ) {
      companion object {
        val Empty =
            Mask(
                maskedLineRanges = emptySet(),
            )
      }
    }
  }

  data class ChangeSet(
      val changeByFilePath: Map<LiteralRelativeUnixPath, Change>,
  ) {
    /** A change to be applied to a code file. */
    sealed class Change {
      /** Patch for an existing file. */
      data class Patch(
          val fragmentByOldLineIndexRange: Map<LineIndexRange, Fragment>,
      ) : Change() {
        data class Fragment(
            val newCodeBlock: CodeBlock,
        ) {
          companion object {
            val Empty =
                Fragment(
                    newCodeBlock = CodeBlock.Empty,
                )
          }
        }

        companion object {
          /** An empty patch that doesn't change any lines in the input code file. */
          val Empty =
              Patch(
                  fragmentByOldLineIndexRange = emptyMap(),
              )
        }

        init {
          require(
              fragmentByOldLineIndexRange.all { (firstRange, _) ->
                fragmentByOldLineIndexRange.none { (secondRange, _) ->
                  firstRange != secondRange && firstRange.overlaps(secondRange)
                }
              },
          ) {
            "Line index ranges in the patch set must not overlap"
          }
        }
      }

      /** Creation of a new file */
      data class Create(
          val content: CodeBlock,
      ) : Change()

      /** Deletion of an existing file */
      object Delete : Change()
    }
  }

  interface PatchGenerator {
    suspend fun generateChanges(
        maskedCodeCatalog: MaskedCodeCatalog,
    ): ChangeSet
  }

  interface CodeMasker {
    fun prepareMask(
        codeFileContent: CodeFileContent,
    ): MaskedCodeFileContent.Mask
  }

  fun patchToCompleteTask(
      taskDescription: String,
  ): PatchGenerator

  fun patchToFixIssues(
      originalTaskDescription: String,
      moduleDiagnosis: CodeModuleDiagnosis.Incorrect,
  ): PatchGenerator
}

fun AiCodeEditor.CodeCatalog.applyMasks(
    masker: CodeMasker,
): MaskedCodeCatalog =
    MaskedCodeCatalog(
        maskedCodeFileContentByPath =
            codeFileContentByPath.mapValues { (_, codeFileContent) ->
              val codeFileMask =
                  masker.prepareMask(
                      codeFileContent = codeFileContent,
                  )

              AiCodePatcher.MaskedCodeFileContent(
                  codeFileContent = codeFileContent,
                  mask = codeFileMask,
              )
            },
    )
