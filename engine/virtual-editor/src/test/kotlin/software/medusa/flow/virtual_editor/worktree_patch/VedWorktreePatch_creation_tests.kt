package software.medusa.flow.virtual_editor.worktree_patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.filesystem.mutation.UfsDirectoryMutation
import software.medusa.commons.unix.filesystem.mutation.UfsFileMutation
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree

class VedWorktreePatch_creation_tests {
  @Test
  fun `apply creates a new file in an existing directory`() {
    val emptyWorktree =
        VedWorktree(rootDirectory = VedExpandedDirectory(labeledEntityByName = emptyMap()))

    val newFileName = UfsName.Literal("New.kt")

    val patch =
        VedWorktreePatch(
            rootDirectoryPatch =
                VedDirectoryPatch(
                    childPatchByName =
                        mapOf(
                            newFileName to
                                VedFilePatch(creationTxtPatch("created line 1", "created line 2")),
                        ),
                ),
        )

    val result = patch.patchWorktree(worktree = emptyWorktree, timestamp = VedTimestamp.zero.next)

    // Virtual side: the new file is present and opened, holding the created content.
    val createdFile =
        result.patchedWorktree.rootDirectory.labeledEntityByName.getValue(newFileName).entity
            as VedOpenedFile

    assertEquals(
        expected = TxtBlock.of("created line 1", "created line 2"),
        actual = createdFile.currentContent.content,
    )

    // Filesystem side: the root mutation creates the new child.
    val rootDive = assertIs<UfsDirectoryMutation.Dive>(result.rootDirectoryMutation)

    assertIs<UfsDirectoryMutation.Dive.Operation.Create>(
        rootDive.operationByName.getValue(newFileName)
    )
  }

  @Test
  fun `apply creates a file together with its missing parent directory`() {
    val emptyWorktree =
        VedWorktree(rootDirectory = VedExpandedDirectory(labeledEntityByName = emptyMap()))

    val newDirectoryName = UfsName.Literal("newdir")
    val deepFileName = UfsName.Literal("Deep.kt")

    val patch =
        VedWorktreePatch(
            rootDirectoryPatch =
                VedDirectoryPatch(
                    childPatchByName =
                        mapOf(
                            newDirectoryName to
                                VedDirectoryPatch(
                                    childPatchByName =
                                        mapOf(
                                            deepFileName to
                                                VedFilePatch(creationTxtPatch("deep content")),
                                        ),
                                ),
                        ),
                ),
        )

    val result = patch.patchWorktree(worktree = emptyWorktree, timestamp = VedTimestamp.zero.next)

    val createdDirectory =
        result.patchedWorktree.rootDirectory.labeledEntityByName.getValue(newDirectoryName).entity
            as VedExpandedDirectory

    val deepFile =
        createdDirectory.labeledEntityByName.getValue(deepFileName).entity as VedOpenedFile

    assertEquals(
        expected = TxtBlock.of("deep content"),
        actual = deepFile.currentContent.content,
    )

    // The whole missing directory is created in one shot at the root.
    val rootDive = assertIs<UfsDirectoryMutation.Dive>(result.rootDirectoryMutation)

    assertIs<UfsDirectoryMutation.Dive.Operation.Create>(
        rootDive.operationByName.getValue(newDirectoryName),
    )
  }

  @Test
  fun `apply deletes an existing file`() {
    val fileName = UfsName.Literal("Obsolete.kt")

    val worktree =
        VedWorktree(
            rootDirectory =
                VedExpandedDirectory(
                    labeledEntityByName =
                        mapOf(
                            fileName to
                                VedExpandedDirectory.LabeledEntity(
                                    status = GitWorktreeEntity.Status.included,
                                    entity =
                                        VedOpenedFile.of(
                                            content = TxtFileContent(content = TxtBlock.of("gone")),
                                            timestamp = VedTimestamp.zero,
                                        ),
                                ),
                        ),
                ),
        )

    val patch =
        VedWorktreePatch(
            rootDirectoryPatch =
                VedDirectoryPatch(childPatchByName = mapOf(fileName to VedEntityDeletion)),
        )

    val result = patch.patchWorktree(worktree = worktree, timestamp = VedTimestamp.zero.next)

    // Virtual side: the file is gone from the worktree.
    assertEquals(
        expected = false,
        actual = result.patchedWorktree.rootDirectory.labeledEntityByName.containsKey(fileName),
    )

    // Filesystem side: the child is deleted.
    val rootDive = assertIs<UfsDirectoryMutation.Dive>(result.rootDirectoryMutation)
    val operation =
        assertIs<UfsDirectoryMutation.Dive.Operation.Mutate>(
            rootDive.operationByName.getValue(fileName),
        )
    assertEquals(expected = UfsFileMutation.Delete, actual = operation.mutation)
  }

  private fun creationTxtPatch(
      vararg lines: String,
  ): TxtPatch =
      TxtPatch(
          fragmentByOldLineIndexRange =
              mapOf(
                  TxtLineIndexRange.of(startIndex = TxtLineIndex.First, length = 0) to
                      TxtPatch.Fragment(newContent = TxtBlock.of(*lines)),
              ),
      )
}
