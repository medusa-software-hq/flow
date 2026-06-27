package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.filesystem.mutation.applyMutation
import software.medusa.flow.harness.HrsTaskCompleter.JointOperationPhase
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.Companion.scoutFully
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.physical_workspace.allocateWorkspace
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.universal_project.UnpProjectManifestLoader

class HrsProperTaskCompleter(
    private val physicalWorkspaceAllocator: PhwWorkspaceAllocator,
    private val aiSystem: HrsFrontlineAiSystem,
    private val projectManifestLoader: UnpProjectManifestLoader,
) : HrsTaskCompleter {
  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult {
    val sourceRootDirectory = sourceGitWorktree.rootDirectory.asFilesystemEntity

    val projectManifest = projectManifestLoader.load(projectDirectory = sourceRootDirectory)

    val physicalWorkspace =
        physicalWorkspaceAllocator.allocateWorkspace(
            templateDirectory = sourceRootDirectory,
        )

    val physicalRootDirectory = physicalWorkspace.rootDirectory

    val projectConnection = projectManifest.connect(physicalWorkspace = physicalWorkspace)

    checkHealthInitially(
            projectConnection = projectConnection,
        )
        ?.let { initialHealthCheckFailure ->
          return initialHealthCheckFailure
        }

    val fullyScoutedWorktree =
        aiSystem
            .scoutFully(
                sourceGitWorktree = sourceGitWorktree,
                taskDescription = taskDescription,
                scoutingObserver = observer.observeScouting(),
            )
            .fullyScoutedWorktree

    val solutionPatch =
        aiSystem
            .implementSolution(
                taskDescription = taskDescription,
                editorWorktree = fullyScoutedWorktree,
            )
            .solutionPatch

    observer.observeImplementedSolution(
        solutionPatch = solutionPatch,
    )

    // Applying the patch yields a filesystem mutation that references only the files the model
    // actually changed. Writing that back touches just those files instead of re-writing every
    // opened file. The full source tree is already materialized above; the next step will run
    // Gradle tasks there.
    val solutionApplicationResult = solutionPatch.apply(worktree = fullyScoutedWorktree)

    physicalRootDirectory.applyMutation(
        mutation = solutionApplicationResult.rootDirectoryMutation,
    )

    checkHealthFinally(
            projectConnection = projectConnection,
        )
        ?.let { finalHealthCheckFailure ->
          return finalHealthCheckFailure
        }

    return TaskCompletionResult.Success(
        temporaryWorkspace =
            HrsPhysicalTemporaryWorkspace(
                physicalWorkspace = physicalWorkspace,
            ),
    )
  }

  private suspend fun checkHealthInitially(
      projectConnection: UnpProjectConnection,
  ): TaskCompletionResult.Failure.JointOperation? {
    val bootstrapResult = projectConnection.bootstrapAll()

    if (bootstrapResult is JointResult.Failure) {
      return TaskCompletionResult.Failure.JointOperation(
          phase = JointOperationPhase.ProjectBootstrapping,
          operationFailure = bootstrapResult,
      )
    }

    val initialAnalyzeResult = projectConnection.analyzeAll()

    if (initialAnalyzeResult is JointResult.Failure) {
      return TaskCompletionResult.Failure.JointOperation(
          phase = JointOperationPhase.InitialProjectAnalysis,
          operationFailure = initialAnalyzeResult,
      )
    }

    val initialTestResult = projectConnection.testAll()

    if (initialTestResult is JointResult.Failure) {
      return TaskCompletionResult.Failure.JointOperation(
          phase = JointOperationPhase.InitialProjectTesting,
          operationFailure = initialTestResult,
      )
    }

    return null
  }

  private suspend fun checkHealthFinally(
      projectConnection: UnpProjectConnection,
  ): TaskCompletionResult.Failure.JointOperation? {
    val finalAnalyzeResult = projectConnection.analyzeAll()

    if (finalAnalyzeResult is JointResult.Failure) {
      return TaskCompletionResult.Failure.JointOperation(
          phase = JointOperationPhase.FinalProjectAnalysis,
          operationFailure = finalAnalyzeResult,
      )
    }

    val finalTestResult = projectConnection.testAll()

    if (finalTestResult is JointResult.Failure) {
      return TaskCompletionResult.Failure.JointOperation(
          phase = JointOperationPhase.FinalProjectTesting,
          operationFailure = finalTestResult,
      )
    }

    return null
  }
}
