package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.copyRecursivelyTo
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree.asFilesystemEntity

class HrsProperTaskCompleter(
    private val temporaryWorkspaceAllocator: HrsProperTemporaryWorkspaceAllocator,
    private val solutionCoder: HrsSolutionCoder,
) : HrsTaskCompleter {
  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
  ): HrsReadonlyTemporaryWorkspace {
    val sourceRootDirectory = sourceGitWorktree.rootDirectory.asFilesystemEntity
    val temporaryWorkspace = temporaryWorkspaceAllocator.allocateTemporaryWorkspace()

    sourceRootDirectory.copyRecursivelyTo(
        targetDirectory = temporaryWorkspace.rootDirectory,
    )

    val baseEditorWorktree =
        VedWorktree.import(
            sourceWorktree = sourceGitWorktree,
        )

    val solutionPatch =
        solutionCoder.codeSolution(
            editorWorktree = baseEditorWorktree,
            taskDescription = taskDescription,
        )

    val finalEditorWorktree = solutionPatch.apply(worktree = baseEditorWorktree)

    // Overlay the edited, opened files back onto the physical workspace.
    val finalRootDirectory: UfsReadonlyDirectory =
        finalEditorWorktree.rootDirectory.asFilesystemEntity

    finalRootDirectory.copyRecursivelyTo(
        targetDirectory = temporaryWorkspace.rootDirectory,
    )

    // For now, copying to a physical workspace might appear extraneous.
    // _But_ the next step will be running Gradle tasks there.

    return temporaryWorkspace
  }
}
