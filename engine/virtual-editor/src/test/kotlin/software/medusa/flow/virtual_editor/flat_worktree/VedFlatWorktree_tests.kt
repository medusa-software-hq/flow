package software.medusa.flow.virtual_editor.flat_worktree

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree

class VedFlatWorktree_tests {
  private fun content(text: String): TxtFileContent = TxtFileContent(content = TxtBlock.of(text))

  private fun labeled(entity: VedEntity): VedExpandedDirectory.LabeledEntity =
      VedExpandedDirectory.LabeledEntity(
          status = GitWorktreeEntity.Status.included,
          entity = entity,
      )

  private fun worktreeOf(
      files: Map<String, VedOpenedFile>,
  ): VedWorktree =
      VedWorktree(
          rootDirectory =
              VedExpandedDirectory(
                  labeledEntityByName =
                      files.entries.associate { (name, file) ->
                        UfsName.Literal(name) to labeled(file)
                      },
              ),
      )

  @Test
  fun `flatten orders files by modification timestamp then path`() {
    val worktree =
        worktreeOf(
            files =
                mapOf(
                    "b.txt" to VedOpenedFile.of(content("B"), timestamp = VedTimestamp(2)),
                    "a.txt" to VedOpenedFile.of(content("A"), timestamp = VedTimestamp(2)),
                    "early.txt" to VedOpenedFile.of(content("E"), timestamp = VedTimestamp(1)),
                ),
        )

    val flat = worktree.flatten()

    assertEquals(
        expected = listOf("/early.txt" to 1, "/a.txt" to 2, "/b.txt" to 2),
        actual =
            flat.openedFiles.map {
              it.path.toUnixAbsolutePathString() to it.modificationTimestamp.t
            },
    )
  }

  @Test
  fun `flatten emits one entry per content version, interleaved by timestamp`() {
    val revisedFile =
        VedOpenedFile.of(content("v1"), timestamp = VedTimestamp(1))
            .update(newContent = content("v2"), timestamp = VedTimestamp(3))

    val worktree =
        worktreeOf(
            files =
                mapOf(
                    "app.txt" to revisedFile,
                    "other.txt" to VedOpenedFile.of(content("O"), timestamp = VedTimestamp(2)),
                ),
        )

    val flat = worktree.flatten()

    // Both revisions of app.txt appear, with other.txt (t=2) interleaved between them.
    assertEquals(
        expected =
            listOf(
                Triple("/app.txt", 1, content("v1")),
                Triple("/other.txt", 2, content("O")),
                Triple("/app.txt", 3, content("v2")),
            ),
        actual =
            flat.openedFiles.map {
              Triple(it.path.toUnixAbsolutePathString(), it.modificationTimestamp.t, it.content)
            },
    )
  }
}
