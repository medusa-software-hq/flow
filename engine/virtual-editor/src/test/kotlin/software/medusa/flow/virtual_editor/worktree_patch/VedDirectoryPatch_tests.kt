package software.medusa.flow.virtual_editor.worktree_patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.filesystem.mutation.UfsDirectoryMutation
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedCollapsedDirectory
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile

class VedDirectoryPatch_tests {
  private val includedStatus = GitWorktreeEntity.Status.included

  private val patchTimestamp = VedTimestamp.zero.next

  private fun openedFile(vararg lines: String) =
      VedOpenedFile.of(
          content = TxtFileContent(content = TxtBlock.of(*lines)),
          timestamp = VedTimestamp.zero,
      )

  private fun labeledFile(vararg lines: String) =
      VedExpandedDirectory.LabeledEntity(status = includedStatus, entity = openedFile(*lines))

  private fun singleLinePatch(newLine: String) =
      VedFilePatch(
          txtPatch =
              TxtPatch(
                  fragmentByOldLineIndexRange =
                      mapOf(
                          TxtLineIndexRange.of(startIndex = TxtLineIndex.First, length = 1) to
                              TxtPatch.Fragment(newContent = TxtBlock.of(newLine)),
                      ),
              ),
      )

  @Test
  fun `apply patches named child`() {
    val name = UfsName.Literal("file.kt")
    val directory =
        VedExpandedDirectory(
            labeledEntityByName = mapOf(name to labeledFile("old line")),
        )

    val patch = VedDirectoryPatch(childPatchByName = mapOf(name to singleLinePatch("new line")))

    val result =
        patch
            .patchExpandedDirectory(directory = directory, timestamp = patchTimestamp)
            .patchedEntity

    val patchedEntity = result.labeledEntityByName.getValue(name).entity as VedOpenedFile
    assertEquals(TxtBlock.of("new line"), patchedEntity.currentContent.content)
  }

  @Test
  fun `apply leaves children without a patch unchanged`() {
    val patchedName = UfsName.Literal("a.kt")
    val untouchedName = UfsName.Literal("b.kt")
    val untouchedFile = openedFile("unchanged line")
    val directory =
        VedExpandedDirectory(
            labeledEntityByName =
                mapOf(
                    patchedName to labeledFile("old line"),
                    untouchedName to
                        VedExpandedDirectory.LabeledEntity(
                            status = includedStatus,
                            entity = untouchedFile,
                        ),
                ),
        )

    val patch =
        VedDirectoryPatch(childPatchByName = mapOf(patchedName to singleLinePatch("new line")))

    val result =
        patch
            .patchExpandedDirectory(directory = directory, timestamp = patchTimestamp)
            .patchedEntity

    assertEquals(untouchedFile, result.labeledEntityByName.getValue(untouchedName).entity)
  }

  @Test
  fun `apply recurses into nested expanded directory`() {
    val childDirName = UfsName.Literal("subdir")
    val fileName = UfsName.Literal("file.kt")
    val innerDirectory =
        VedExpandedDirectory(
            labeledEntityByName = mapOf(fileName to labeledFile("old line")),
        )
    val outerDirectory =
        VedExpandedDirectory(
            labeledEntityByName =
                mapOf(
                    childDirName to
                        VedExpandedDirectory.LabeledEntity(
                            status = includedStatus,
                            entity = innerDirectory,
                        ),
                ),
        )

    val patch =
        VedDirectoryPatch(
            childPatchByName =
                mapOf(
                    childDirName to
                        VedDirectoryPatch(
                            childPatchByName = mapOf(fileName to singleLinePatch("new line")),
                        ),
                ),
        )

    val result =
        patch
            .patchExpandedDirectory(directory = outerDirectory, timestamp = patchTimestamp)
            .patchedEntity

    val resultInner =
        result.labeledEntityByName.getValue(childDirName).entity as VedExpandedDirectory
    val patchedFile = resultInner.labeledEntityByName.getValue(fileName).entity as VedOpenedFile
    assertEquals(TxtBlock.of("new line"), patchedFile.currentContent.content)
  }

  @Test
  fun `apply produces a mutation only for patched children`() {
    val patchedName = UfsName.Literal("a.kt")
    val untouchedName = UfsName.Literal("b.kt")
    val directory =
        VedExpandedDirectory(
            labeledEntityByName =
                mapOf(
                    patchedName to labeledFile("old line"),
                    untouchedName to labeledFile("unchanged line"),
                ),
        )

    val patch =
        VedDirectoryPatch(childPatchByName = mapOf(patchedName to singleLinePatch("new line")))

    val mutation =
        patch
            .patchExpandedDirectory(directory = directory, timestamp = patchTimestamp)
            .entityMutation as UfsDirectoryMutation.Dive

    assertEquals(setOf(patchedName), mutation.operationByName.keys)
  }

  @Test
  fun `apply throws on collapsed directory`() {
    val patch = VedDirectoryPatch(childPatchByName = emptyMap())

    assertFailsWith<IllegalStateException> {
      patch.patchDirectory(directory = VedCollapsedDirectory, timestamp = patchTimestamp)
    }
  }
}
