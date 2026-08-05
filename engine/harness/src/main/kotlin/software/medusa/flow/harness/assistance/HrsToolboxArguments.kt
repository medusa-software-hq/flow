package software.medusa.flow.harness.assistance

import kotlinx.schema.Description
import kotlinx.serialization.Serializable

/**
 * Shared argument shape for `expand_directory`, `peek_file`, `open_file`, `expose_file`,
 * `hide_file`.
 */
@Serializable
internal data class HrsToolPathArgs(
    @Description(
        "Absolute path, exactly as shown in the worktree tree, e.g. `/src/Main.kt`. MUST start with a leading '/'.",
    )
    val path: String,
)

/** `run_checks` takes no arguments; the model must still pass an (empty) object. */
@Serializable internal data object HrsToolRunChecksArgs

@Serializable
internal data class HrsToolPatchArgs(
    @Description(
        "Existing files to edit, full new content replacing the old. Opens a currently-closed " +
            "file automatically before editing it.",
    )
    val editedFiles: List<FileWrite> = emptyList(),
    @Description("New files to create. Each must NOT already exist.") //
    val createdFiles: List<FileWrite> = emptyList(),
    @Description("Files to delete. Each must already exist.") //
    val deletedFiles: List<FilePath> = emptyList(),
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
}
