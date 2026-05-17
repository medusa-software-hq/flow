package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.EditionInstructions
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.EditionScope
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndex
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.flow.core_service.worker.code.CodeFileContent
import software.medusa.flow.core_service.worker.code_project.CodeProject
import software.medusa.flow.core_service.worker.code_project.readFile
import software.medusa.flow.core_service.worker.code_project.updateFile
import software.medusa.flow.core_service.worker.utils.isSortedBy

/**
 * Generates a set of patches to be applied to one or more code files in accordance with the
 * provided edition instructions.
 */
interface AiCodeEditor {
  /** Content of a code file with certain blocks of lines masked from the AI code engineer. */
  data class MaskedCodeFileContent(
      /**
       * A list of content and mask blocks that together represent the content of the code file.
       * Typically, the content blocks are interleaved with the mask blocks, but it's not strictly
       * required. The content blocks must be sorted by their start line indices and must not
       * overlap.
       */
      val blocks: List<Block>,
  ) {
    sealed interface Block

    /**
     * A block of content in the code file associated with a known line range that should be visible
     * to the AI code engineer and can be edited by it.
     */
    data class ContentBlock(
        val startIndex: LineIndex,
        val content: CodeBlock,
    ) : Block {
      val lineIndexRange: LineIndexRange
        get() =
            LineIndexRange.of(
                startIndex = startIndex,
                length = content.lineCount,
            )

      val indexedLines: Sequence<CodeBlock.IndexedLine>
        get() =
            content.buildIndexedLines(
                baseIndex = startIndex,
            )
    }

    /**
     * A block of content in the code file that should be masked from the AI code engineer and
     * cannot be edited by it. Not explicitly associated with any line range in the code file.
     */
    data class MaskBlock(
        /**
         * A summary of the masked content that should be visible to the AI code engineer (which is
         * code block itself).
         */
        val summary: CodeBlock,
    ) : Block

    companion object {
      /**
       * Creates a masked code file content with a single content block containing the given
       * [fileContent] without any actual masking.
       */
      fun of(
          fileContent: CodeFileContent,
      ): MaskedCodeFileContent =
          MaskedCodeFileContent(
              blocks =
                  listOf(
                      ContentBlock(
                          startIndex = LineIndex.First,
                          content = fileContent.code,
                      ),
                  ),
          )

      fun of(
          vararg blocks: Block,
      ): MaskedCodeFileContent =
          MaskedCodeFileContent(
              blocks = blocks.toList(),
          )
    }

    init {
      val contentBlocks = blocks.mapNotNull { it as? ContentBlock }

      require(contentBlocks.isSortedBy { it.startIndex }) {
        "Content blocks must be sorted by start index"
      }

      val contentBlockRanges = contentBlocks.map { it.lineIndexRange }

      require(
          contentBlockRanges.withIndex().all { (firstIndex, firstRange) ->
            contentBlockRanges.withIndex().none { (secondIndex, secondRange) ->
              firstIndex != secondIndex && firstRange.overlaps(secondRange)
            }
          },
      ) {
        "Content blocks must not overlap"
      }
    }
  }

  /** Represents a set of patches to be applied to multiple code files. */
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

  /** Represents a patch to be applied to a code file. */
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
      val newCodeBlockByOldLineIndexRange: Map<LineIndexRange, CodeBlock>,
  ) {
    companion object {
      /** An empty patch that doesn't change any lines in the input code file. */
      val Empty =
          Patch(
              newCodeBlockByOldLineIndexRange = emptyMap(),
          )
    }

    init {
      require(
          newCodeBlockByOldLineIndexRange.all { (firstRange, _) ->
            newCodeBlockByOldLineIndexRange.none { (secondRange, _) ->
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

  @JvmInline
  value class EditionInstructions(
      val instructions: CodeBlock,
  ) {
    fun toMarkdownBlock(): CodeBlock {
      return CodeBlock.concat()
    }
  }

  data class EditionScope(
      val maskedCodeFileContentByPath: Map<LiteralRelativeUnixPath, MaskedCodeFileContent>,
  )

  suspend fun generateEditionPatchSet(
      editionInstructions: EditionInstructions,
      editionScope: EditionScope,
  ): PatchSet
}

suspend fun AiCodeEditor.editCode(
    codeProject: CodeProject,
    editionInstructions: EditionInstructions,
    editionScope: EditionScope,
) {
  val patchSet =
      generateEditionPatchSet(
          editionInstructions = editionInstructions,
          editionScope = editionScope,
      )

  patchSet.applyTo(codeProject = codeProject)
}
