package software.medusa.flow.harness.ai_system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * Covers [HrsRawWorktreePatch.toFullWorktreePatch]'s path handling — specifically the case that
 * broke a real worker run: the frontline model omitting the leading `/` from an otherwise-valid
 * path.
 */
class HrsRawWorktreePatch_tests {
  private fun emptyWorktree(): VedWorktree =
      VedWorktree(rootDirectory = VedExpandedDirectory(labeledEntityByName = emptyMap()))

  @Test
  fun `creating a file at a path missing the leading slash is normalized, not rejected`() {
    val raw =
        HrsRawWorktreePatch(
            editedFiles = emptyList(),
            createdFiles =
                listOf(
                    HrsRawWorktreePatch.FileWrite(path = "README.md", newContent = "hello"),
                ),
            deletedFiles = emptyList(),
        )

    val patch = raw.toFullWorktreePatch(baseWorktree = emptyWorktree())

    val result = patch.patchWorktree(worktree = emptyWorktree(), timestamp = VedTimestamp(1))

    assertEquals(
        listOf("/README.md"),
        result.patchedWorktree.flatten().openedFiles.map { it.path.toUnixAbsolutePathString() },
    )
  }

  @Test
  fun `creating a file at a normal absolute path still works`() {
    val raw =
        HrsRawWorktreePatch(
            editedFiles = emptyList(),
            createdFiles =
                listOf(
                    HrsRawWorktreePatch.FileWrite(path = "/README.md", newContent = "hello"),
                ),
            deletedFiles = emptyList(),
        )

    val patch = raw.toFullWorktreePatch(baseWorktree = emptyWorktree())

    val result = patch.patchWorktree(worktree = emptyWorktree(), timestamp = VedTimestamp(1))

    assertEquals(
        listOf("/README.md"),
        result.patchedWorktree.flatten().openedFiles.map { it.path.toUnixAbsolutePathString() },
    )
  }

  @Test
  fun `editing a file that was never opened still fails clearly`() {
    val raw =
        HrsRawWorktreePatch(
            editedFiles =
                listOf(
                    HrsRawWorktreePatch.FileWrite(path = "/README.md", newContent = "hello"),
                ),
            createdFiles = emptyList(),
            deletedFiles = emptyList(),
        )

    assertFailsWith<IllegalArgumentException> {
      raw.toFullWorktreePatch(baseWorktree = emptyWorktree())
    }
  }
}
