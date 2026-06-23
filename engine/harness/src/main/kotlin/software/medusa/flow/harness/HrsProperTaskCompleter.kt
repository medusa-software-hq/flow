package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.filesystem.copyRecursivelyTo
import software.medusa.commons.unix.filesystem.mutation.applyMutation
import software.medusa.flow.virtual_editor.worktree.VedWorktree

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

    // Applying the patch yields a filesystem mutation that references only the files the model
    // actually changed. Writing that back touches just those files instead of re-writing every
    // opened file. The full source tree is already materialized above; the next step will run
    // Gradle tasks there.
    val solutionApplicationResult = solutionPatch.apply(worktree = baseEditorWorktree)

    temporaryWorkspace.rootDirectory.applyMutation(
        mutation = solutionApplicationResult.rootDirectoryMutation,
    )

    return temporaryWorkspace
  }
}
