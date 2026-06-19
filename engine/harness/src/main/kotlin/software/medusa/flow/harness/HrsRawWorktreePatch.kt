package software.medusa.flow.harness

import kotlinx.schema.Description
import kotlinx.serialization.Serializable
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedEntityPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

/**
 * The raw, LLM-facing shape of a worktree patch: a flat list of whole-file edits.
 *
 * Intentionally minimal for the single-round, single-request proof of concept. The model rewrites
 * the full content of already-opened files rather than producing fine-grained diffs, which keeps
 * the structured-output schema trivial. [toFullWorktreePatch] turns this flat shape into the nested
 * [VedWorktreePatch] the virtual editor understands, computing each whole-file replacement against
 * the file's current content in [baseWorktree].
 */
@Serializable
internal data class HrsRawWorktreePatch(
    @Description(
        "The files to edit. Each entry replaces the full content of an already-opened file."
    )
    val editedFiles: List<EditedFile>,
) {
  @Serializable
  data class EditedFile(
      @Description(
          "Absolute path of the file to edit, exactly as shown in the worktree, e.g. `/src/Main.kt`.",
      )
      val path: String,
      @Description("The full new content of the file.") //
      val newContent: String,
  )

  private data class ParsedEdit(
      val names: List<UfsName.Literal>,
      val filePatch: VedFilePatch,
  )

  fun toFullWorktreePatch(
      baseWorktree: VedWorktree,
  ): VedWorktreePatch {
    val parsedEdits = editedFiles.map { editedFile ->
      val names = parseFilePath(editedFile.path)

      val baseFile =
          baseWorktree.rootDirectory.findOpenedFile(names)
              ?: throw IllegalArgumentException(
                  "Cannot edit `${editedFile.path}`: no opened file exists at that path.",
              )

      ParsedEdit(
          names = names,
          filePatch =
              buildWholeFileReplacement(
                  baseFile = baseFile,
                  newContent = editedFile.newContent,
              ),
      )
    }

    return VedWorktreePatch(
        rootDirectoryPatch = buildDirectoryPatch(edits = parsedEdits),
    )
  }

  private fun parseFilePath(
      path: String,
  ): List<UfsName.Literal> {
    val literalPath =
        UfsAbsolutePath.parse(path).toLiteral()
            ?: throw IllegalArgumentException("Path `$path` is not a valid literal absolute path.")

    val names = literalPath.innerPath.names

    require(names.isNotEmpty()) { "Path `$path` does not point to a file." }

    return names
  }

  private fun VedExpandedDirectory.findOpenedFile(
      names: List<UfsName.Literal>,
  ): VedOpenedFile? {
    val head = names.firstOrNull() ?: return null
    val childEntity = labeledEntityByName[head]?.entity ?: return null

    return when (names.size) {
      1 -> childEntity as? VedOpenedFile
      else -> (childEntity as? VedExpandedDirectory)?.findOpenedFile(names.drop(1))
    }
  }

  private fun buildWholeFileReplacement(
      baseFile: VedOpenedFile,
      newContent: String,
  ): VedFilePatch {
    val oldLineCount = baseFile.content.content.height

    return VedFilePatch(
        txtPatch =
            TxtPatch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        TxtLineIndexRange.of(
                            startIndex = TxtLineIndex.First,
                            length = oldLineCount,
                        ) to TxtPatch.Fragment(newContent = TxtBlock.parse(newContent)),
                    ),
            ),
    )
  }

  private fun buildDirectoryPatch(
      edits: List<ParsedEdit>,
  ): VedDirectoryPatch {
    val childPatchByName: Map<UfsName.Literal, VedEntityPatch> =
        edits
            .groupBy { edit -> edit.names.first() }
            .mapValues { (_, group) ->
              val fileEdit = group.firstOrNull { edit -> edit.names.size == 1 }

              if (fileEdit != null) {
                fileEdit.filePatch
              } else {
                buildDirectoryPatch(
                    edits = group.map { edit -> edit.copy(names = edit.names.drop(1)) }
                )
              }
            }

    return VedDirectoryPatch(childPatchByName = childPatchByName)
  }
}
