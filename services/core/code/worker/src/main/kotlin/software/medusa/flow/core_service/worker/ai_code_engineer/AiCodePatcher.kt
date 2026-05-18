package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.CodeMasker
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.flow.core_service.worker.code.CodeFileContent
import software.medusa.flow.core_service.worker.code_project.CodeProject
import software.medusa.flow.core_service.worker.code_project.CodeTool.CodeModuleDiagnosis
import software.medusa.flow.core_service.worker.code_project.readFile
import software.medusa.flow.core_service.worker.code_project.updateFile

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

  /** A set of patches to be applied to multiple code files. */
  data class PatchSet(
      val patchByFilePath: Map<LiteralRelativeUnixPath, Patch>,
  ) {
    suspend fun applyTo(
        codeProject: CodeProject,
    ) {
      patchByFilePath.forEach { (filePath, patch) ->
        patch.applyTo(
            codeProject = codeProject,
            filePath = filePath,
        )
      }
    }
  }

  /** A patch to be applied to a code file. */
  data class Patch(
      /**
       * Mapping from line index ranges in the old code file to new code blocks that should replace
       * the lines in those ranges. The line index ranges in the map must not overlap, but they can
       * be adjacent or have gaps between them.
       *
       * If there are gaps between the line index ranges, it means that the lines in those gaps
       * should remain unchanged.
       *
       * Removing lines can be represented by mapping a line index range to an empty code block,
       * while adding new lines can be represented by mapping an empty line index range to a
       * non-empty code block.
       *
       * Appending new lines at the end of the file can be represented by mapping an empty line
       * index range starting at the line index equal to the old file's line count to a non-empty
       * code block.
       */
      val fragmentByOldLineIndexRange: Map<LineIndexRange, Fragment>,
  ) {
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

    suspend fun applyTo(
        codeProject: CodeProject,
        filePath: LiteralRelativeUnixPath,
    ) {
      val oldContent = codeProject.readFile(filePath = filePath)

      val patchedContent = oldContent.applyPatch(patch = this)

      codeProject.updateFile(
          filePath = filePath,
          newFileContent = patchedContent,
      )
    }
  }

  interface PatchGenerator {
    suspend fun generatePatches(
        maskedCodeCatalog: MaskedCodeCatalog,
    ): PatchSet
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
