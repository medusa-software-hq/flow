package software.medusa.flow.virtual_editor.worktree_patch

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree

class VedWorktreePatch_tests {
  @Test
  fun `apply patches opened file in root directory`() {
    val fileName = UfsName.Literal("Main.kt")
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
                                        VedOpenedFile(
                                            content =
                                                TxtFileContent(
                                                    content = TxtBlock.of("fun main() {}", "}"),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    val patch =
        VedWorktreePatch(
            rootDirectoryPatch =
                VedDirectoryPatch(
                    childPatchByName =
                        mapOf(
                            fileName to
                                VedFilePatch(
                                    txtPatch =
                                        TxtPatch(
                                            fragmentByOldLineIndexRange =
                                                mapOf(
                                                    TxtLineIndexRange.of(
                                                        startIndex = TxtLineIndex.First,
                                                        length = 1,
                                                    ) to
                                                        TxtPatch.Fragment(
                                                            newContent =
                                                                TxtBlock.of(
                                                                    "fun main(args: Array<String>) {"
                                                                ),
                                                        ),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    val result = patch.apply(worktree)

    val patchedFile =
        result.rootDirectory.labeledEntityByName.getValue(fileName).entity as VedOpenedFile
    assertEquals(
        TxtBlock.of("fun main(args: Array<String>) {", "}"),
        patchedFile.content.content,
    )
  }
}
