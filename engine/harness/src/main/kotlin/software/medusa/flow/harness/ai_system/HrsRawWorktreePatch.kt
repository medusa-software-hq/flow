package software.medusa.flow.harness.ai_system

import kotlinx.schema.Description
import kotlinx.serialization.Serializable
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedFile
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedEntityDeletion
import software.medusa.flow.virtual_editor.worktree_patch.VedEntityPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

/**
 * The raw, LLM-facing shape of a worktree patch: separate lists of files to edit, create, and
 * delete.
 *
 * The patch interpreter asks a model to sort the frontline's free-form patch message into these
 * three explicit operations, which sidesteps fragile line-number diffs. [toFullWorktreePatch] turns
 * this flat shape into the nested [VedWorktreePatch] the virtual editor understands — strictly: an
 * edit must target an existing opened file, a creation must target a path with nothing there, and a
 * deletion must target an existing file.
 */
@Serializable
internal data class HrsRawWorktreePatch(
    @Description(
        "Existing files to edit. Each entry replaces the file's full content. The file must already exist.",
    )
    val editedFiles: List<FileWrite>,
    @Description(
        "New files to create. Each entry writes the file's full content. The file must NOT already exist.",
    )
    val createdFiles: List<FileWrite>,
    @Description("Files to delete. Each must already exist.") //
    val deletedFiles: List<FilePath>,
) {
  @Serializable
  data class FileWrite(
      @Description(
          "Absolute path of the file, exactly as shown in the worktree. MUST start with a " +
              "leading '/' character, e.g. `/src/Main.kt` — never `src/Main.kt`.",
      )
      val path: String,
      @Description("The full new content of the file.") //
      val newContent: String,
  )

  @Serializable
  data class FilePath(
      @Description(
          "Absolute path of the file, exactly as shown in the worktree. MUST start with a " +
              "leading '/' character, e.g. `/src/Main.kt` — never `src/Main.kt`.",
      )
      val path: String,
  )

  private data class ParsedEntry(
      val names: List<UfsName.Literal>,
      val patch: VedEntityPatch,
  )

  fun toFullWorktreePatch(
      baseWorktree: VedWorktree,
  ): VedWorktreePatch {
    val rootDirectory = baseWorktree.rootDirectory

    val editEntries = editedFiles.map { fileWrite ->
      val names = parseFilePath(fileWrite.path)

      val existingFile =
          rootDirectory.findOpenedFile(names)
              ?: throw IllegalArgumentException(
                  "Cannot edit `${fileWrite.path}`: no opened file exists at that path.",
              )

      ParsedEntry(
          names = names,
          patch =
              buildWholeFileReplacement(baseFile = existingFile, newContent = fileWrite.newContent),
      )
    }

    val creationEntries = createdFiles.map { fileWrite ->
      val names = parseFilePath(fileWrite.path)

      require(rootDirectory.findEntity(names) == null) {
        "Cannot create `${fileWrite.path}`: an entity already exists at that path."
      }

      ParsedEntry(
          names = names,
          patch = buildFileCreation(newContent = fileWrite.newContent),
      )
    }

    val deletionEntries = deletedFiles.map { filePath ->
      val names = parseFilePath(filePath.path)

      require(rootDirectory.findEntity(names) is VedFile) {
        "Cannot delete `${filePath.path}`: no file exists at that path."
      }

      ParsedEntry(names = names, patch = VedEntityDeletion)
    }

    return VedWorktreePatch(
        rootDirectoryPatch =
            buildDirectoryPatch(entries = editEntries + creationEntries + deletionEntries),
    )
  }

  private fun parseFilePath(
      path: String,
  ): List<UfsName.Literal> {
    // The model is asked for absolute paths but occasionally drops the leading '/' — normalize
    // rather than fail the whole attempt over a formatting slip.
    val normalizedPath = if (path.startsWith("/")) path else "/$path"

    val literalPath =
        UfsAbsolutePath.parse(normalizedPath).toLiteral()
            ?: throw IllegalArgumentException("Path `$path` is not a valid literal absolute path.")

    val names = literalPath.innerPath.names

    require(names.isNotEmpty()) { "Path `$path` does not point to a file." }

    return names
  }

  private fun VedExpandedDirectory.findOpenedFile(
      names: List<UfsName.Literal>,
  ): VedOpenedFile? = findEntity(names = names) as? VedOpenedFile

  private fun VedExpandedDirectory.findEntity(
      names: List<UfsName.Literal>,
  ): VedEntity? {
    val head = names.firstOrNull() ?: return null
    val childEntity = labeledEntityByName[head]?.entity ?: return null

    return when (names.size) {
      1 -> childEntity
      else -> (childEntity as? VedExpandedDirectory)?.findEntity(names = names.drop(1))
    }
  }

  private fun buildWholeFileReplacement(
      baseFile: VedOpenedFile,
      newContent: String,
  ): VedFilePatch =
      wholeFileReplacement(
          oldLineCount = baseFile.currentContent.content.height,
          newContent = newContent,
      )

  private fun buildFileCreation(
      newContent: String,
  ): VedFilePatch = wholeFileReplacement(oldLineCount = 0, newContent = newContent)

  /**
   * A patch that replaces [oldLineCount] existing lines with [newContent]. With `oldLineCount = 0`
   * this is an insertion into empty content, i.e. a file creation.
   */
  private fun wholeFileReplacement(
      oldLineCount: Int,
      newContent: String,
  ): VedFilePatch =
      VedFilePatch(
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

  private fun buildDirectoryPatch(
      entries: List<ParsedEntry>,
  ): VedDirectoryPatch {
    val childPatchByName: Map<UfsName.Literal, VedEntityPatch> =
        entries
            .groupBy { entry -> entry.names.first() }
            .mapValues { (_, group) ->
              val leafEntry = group.firstOrNull { entry -> entry.names.size == 1 }

              leafEntry?.patch
                  ?: buildDirectoryPatch(
                      entries = group.map { entry -> entry.copy(names = entry.names.drop(1)) },
                  )
            }

    return VedDirectoryPatch(childPatchByName = childPatchByName)
  }
}
