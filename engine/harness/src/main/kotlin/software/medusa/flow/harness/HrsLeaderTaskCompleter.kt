package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsTaskCompleter.JointOperationPhase
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.assistance.HrsAssistanceContext
import software.medusa.flow.harness.assistance.HrsAssistant
import software.medusa.flow.harness.assistance.HrsToolboxFactory
import software.medusa.flow.harness.history.HrsChunkConfig
import software.medusa.flow.harness.history.HrsDelegationEntry
import software.medusa.flow.harness.history.HrsDelegationLog
import software.medusa.flow.harness.leadership.HrsLeader
import software.medusa.flow.harness.leadership.HrsLeaderCommand
import software.medusa.flow.harness.leadership.HrsLeaderContext
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.physical_workspace.allocateWorkspace
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.universal_project.UnpProjectManifest
import software.medusa.flow.universal_project.UnpProjectManifestLoader
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedEntityAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment

/**
 * The M3 leader/assistant engine's [HrsTaskCompleter]: shares [HrsProperTaskCompleter]'s early
 * pipeline (manifest load, physical workspace allocation, project connection, initial health gate),
 * then replaces the classic scout/expert/implement pipeline with the leader/assistant turn grammar
 * — a leader ([HrsLeader], story 05) that either [HrsLeaderCommand.Stop]s or hands a
 * [HrsLeaderCommand.Delegate] to an assistant ([HrsAssistant], story 03), whose thread runs against
 * a gated toolbox ([software.medusa.flow.harness.assistance.HrsToolbox], story 04). Every
 * delegation closes the outer [HrsDelegationLog] with `(taskDefinition, report)` before the leader
 * is asked again — the loop never asks the leader twice in a row without a delegation in between.
 *
 * A [HrsLeaderCommand.Delegate]'s `hideList` is applied mechanically — no model round, no judgment
 * — before the delegation starts; a stale or malformed entry is skipped rather than failing the run
 * (see [applyHide]).
 *
 * [maxDelegations] is a generous budget on leader turns (≈ delegation count, the milestone's own
 * cost driver). Exhausting it — or the leader itself giving up ([HrsLeader.Result.Failed]) — does
 * not throw: a final authoritative health check decides between [TaskCompletionResult.Success] (the
 * budget ran out, but the project happens to be healthy) and
 * [TaskCompletionResult.Failure.AttemptsExhausted] — the exact same M1-shaped failure type
 * [HrsProperTaskCompleter] returns for its own exhausted-attempts case, so worker code that already
 * renders it needs no changes for this engine.
 */
class HrsLeaderTaskCompleter(
    private val physicalWorkspaceAllocator: PhwWorkspaceAllocator,
    private val projectManifestLoader: UnpProjectManifestLoader,
    private val leader: HrsLeader,
    private val assistant: HrsAssistant,
    private val toolboxFactory: HrsToolboxFactory,
    private val softBudgetTokens: Int = defaultSoftBudgetTokens,
    private val chunkConfig: HrsChunkConfig = HrsChunkConfig.default,
    private val maxDelegations: Int = defaultMaxDelegations,
) : HrsTaskCompleter {
  companion object {
    const val defaultSoftBudgetTokens = 20_000

    /** A generous cap on leader turns — exhausting it is a safety net, not an expected outcome. */
    const val defaultMaxDelegations = 50
  }

  /** The recursive lead loop's own result, translated into a [TaskCompletionResult] once done. */
  private sealed class LeadOutcome {
    data class Stopped(
        val finalWorktree: VedWorktree,
    ) : LeadOutcome()

    /** The budget ran out, or the leader itself gave up — either way, no more delegations. */
    data class GaveUp(
        val delegationsMade: Int,
    ) : LeadOutcome()
  }

  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult {
    observer.observePhase(phase = HrsPipelinePhase.WorkspacePreparing)

    val sourceRootDirectory = sourceGitWorktree.rootDirectory.asFilteredFilesystemEntity

    val projectManifest = projectManifestLoader.load(projectDirectory = sourceRootDirectory)

    val physicalWorkspace =
        physicalWorkspaceAllocator.allocateWorkspace(
            templateDirectory = sourceRootDirectory,
        )

    // Mirrors HrsProperTaskCompleter: only a Success return hands the workspace off; every other
    // exit (structured failure, thrown exception, cancellation) must close it here.
    return physicalWorkspace.closeUnlessSuccessful {
      runPipeline(
          sourceGitWorktree = sourceGitWorktree,
          taskDescription = taskDescription,
          observer = observer,
          projectManifest = projectManifest,
          physicalWorkspace = physicalWorkspace,
      )
    }
  }

  private suspend fun runPipeline(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
      projectManifest: UnpProjectManifest,
      physicalWorkspace: PhwWorkspace,
  ): TaskCompletionResult {
    val physicalRootDirectory = physicalWorkspace.rootDirectory

    val projectConnection = projectManifest.connect(physicalWorkspace = physicalWorkspace)

    observer.observePhase(phase = HrsPipelinePhase.HealthGate)

    checkHealthInitially(
            projectConnection = projectConnection,
        )
        ?.let { initialHealthCheckFailure ->
          return initialHealthCheckFailure
        }

    val leadOutcome =
        continueLeadingRecursively(
            taskDescription = taskDescription,
            baseWorktree = VedWorktree.import(sourceWorktree = sourceGitWorktree),
            baseDelegationLog = HrsDelegationLog(),
            sourceGitWorktree = sourceGitWorktree,
            physicalRootDirectory = physicalRootDirectory,
            projectConnection = projectConnection,
            delegationCount = 0,
        )

    return when (leadOutcome) {
      is LeadOutcome.Stopped ->
          TaskCompletionResult.Success(
              temporaryWorkspace =
                  HrsPhysicalTemporaryWorkspace(
                      physicalWorkspace = physicalWorkspace,
                  ),
          )

      is LeadOutcome.GaveUp ->
          finishByFinalHealthCheck(
              projectConnection = projectConnection,
              delegationsMade = leadOutcome.delegationsMade,
              physicalWorkspace = physicalWorkspace,
          )
    }
  }

  /**
   * The turn grammar itself: ask the leader; a [HrsLeaderCommand.Stop] (or the leader giving up)
   * ends the branch; a [HrsLeaderCommand.Delegate] hides its mechanical cleanup list, runs exactly
   * one assistant thread, closes the log with the result, and only then asks the leader again — so
   * the leader is never asked twice in a row without a delegation in between.
   */
  private tailrec suspend fun continueLeadingRecursively(
      taskDescription: HrsTaskDescription,
      baseWorktree: VedWorktree,
      baseDelegationLog: HrsDelegationLog,
      sourceGitWorktree: GitWorktree,
      physicalRootDirectory: UfsMutableDirectory,
      projectConnection: UnpProjectConnection,
      delegationCount: Int,
  ): LeadOutcome {
    if (delegationCount >= maxDelegations) {
      return LeadOutcome.GaveUp(delegationsMade = delegationCount)
    }

    val leaderContext =
        HrsLeaderContext(
            mainTask = taskDescription,
            delegationLog = baseDelegationLog,
            worktree = baseWorktree,
            softBudgetTokens = softBudgetTokens,
            chunkConfig = chunkConfig,
        )

    return when (val decision = leader.decide(context = leaderContext)) {
      // The leader could not produce a valid command — nothing more to try; land wherever the
      // project currently is, same as running out of the delegation budget.
      is HrsLeader.Result.Failed -> LeadOutcome.GaveUp(delegationsMade = delegationCount)

      is HrsLeader.Result.Decided ->
          when (val command = decision.command) {
            HrsLeaderCommand.Stop -> LeadOutcome.Stopped(finalWorktree = baseWorktree)

            is HrsLeaderCommand.Delegate -> {
              val hiddenWorktree =
                  applyHideList(
                      hideList = command.hideList,
                      worktree = baseWorktree,
                      gitWorktree = sourceGitWorktree,
                  )

              val toolbox =
                  toolboxFactory.create(
                      gitWorktree = sourceGitWorktree,
                      physicalRootDirectory = physicalRootDirectory,
                      projectConnection = projectConnection,
                      // A delegation's index in the (not-yet-appended) log is its timestamp — see
                      // HrsDelegationLog: entry k is delegation t=k.
                      delegationTimestamp = VedTimestamp(t = baseDelegationLog.size),
                  )

              val assistantResult =
                  assistant.runDelegation(
                      context =
                          HrsAssistanceContext(
                              mainTask = taskDescription,
                              delegationLog = baseDelegationLog,
                              worktree = hiddenWorktree,
                          ),
                      taskDefinition = command.taskDefinition,
                      toolbox = toolbox,
                  )

              continueLeadingRecursively(
                  taskDescription = taskDescription,
                  baseWorktree = assistantResult.finalWorktree,
                  baseDelegationLog =
                      baseDelegationLog.append(
                          entry =
                              HrsDelegationEntry(
                                  taskDefinition = command.taskDefinition,
                                  report = assistantResult.report,
                              ),
                      ),
                  sourceGitWorktree = sourceGitWorktree,
                  physicalRootDirectory = physicalRootDirectory,
                  projectConnection = projectConnection,
                  delegationCount = delegationCount + 1,
              )
            }
          }
    }
  }

  /**
   * Applies every hide-list path in order. Each is independent — a bad entry earlier in the list
   * never blocks a good one later.
   */
  private suspend fun applyHideList(
      hideList: List<String>,
      worktree: VedWorktree,
      gitWorktree: GitWorktree,
  ): VedWorktree =
      hideList.fold(worktree) { currentWorktree, path ->
        applyHide(path = path, worktree = currentWorktree, gitWorktree = gitWorktree)
      }

  /**
   * Hides a single leader-board path, mechanically (see [VedFileAdjustment.Hide]). The leader emits
   * these paths as free-form strings off its own rendered board, so a stale or malformed entry — a
   * typo, a path under a directory that was never expanded, a file that isn't open, one that's
   * already hidden — is a realistic failure mode. This is deterministic buffer cleanup, not a model
   * turn that could be bounced feedback; there is nowhere to report a rejection, so a bad entry is
   * simply skipped rather than failing the whole delegation.
   */
  private suspend fun applyHide(
      path: String,
      worktree: VedWorktree,
      gitWorktree: GitWorktree,
  ): VedWorktree =
      try {
        val names = parseWorktreePath(path = path)

        val adjustment =
            VedWorktreeAdjustment(
                rootDirectoryAdjustment =
                    buildSingleLeafDive(names = names, leaf = VedFileAdjustment.Hide),
            )

        adjustment
            .adjust(
                gitWorktree = gitWorktree,
                editorWorktree = worktree,
                // Hide is a pure exposure toggle — it never touches content version history, so the
                // timestamp it's stamped with is inert; any value satisfies the adjustment API.
                timestamp = VedTimestamp.zero,
            )
            .adjustedWorktree
      } catch (e: IllegalArgumentException) {
        worktree
      } catch (e: IllegalStateException) {
        worktree
      }

  private fun buildSingleLeafDive(
      names: List<UfsName.Literal>,
      leaf: VedEntityAdjustment,
  ): VedDirectoryAdjustment.Dive =
      if (names.size == 1) {
        VedDirectoryAdjustment.Dive(childAdjustmentByName = mapOf(names.single() to leaf))
      } else {
        VedDirectoryAdjustment.Dive(
            childAdjustmentByName =
                mapOf(names.first() to buildSingleLeafDive(names = names.drop(1), leaf = leaf)),
        )
      }

  private fun parseWorktreePath(
      path: String,
  ): List<UfsName.Literal> {
    // The leader is asked for absolute paths but may drop the leading '/' — normalize rather than
    // reject the whole entry over a formatting slip (mirrors HrsProperToolbox.parsePath).
    val normalizedPath = if (path.startsWith("/")) path else "/$path"

    val literalPath =
        UfsAbsolutePath.parse(normalizedPath).toLiteral()
            ?: throw IllegalArgumentException("Path `$path` is not a valid literal absolute path.")

    val names = literalPath.innerPath.names

    require(names.isNotEmpty()) { "Path `$path` does not point to an entity." }

    return names
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

    // Formatting is mechanical and deterministic: normalize the workspace before the analyze gate
    // checks it, instead of failing the gate over a formatting-only miss (mirrors
    // HrsProperTaskCompleter.checkHealthInitially).
    projectConnection.normalizeAll()

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

  /**
   * The last-resort verdict once the lead loop gives up (budget exhausted, or the leader itself
   * failed): the delegation budget spent is not, by itself, a failure — only a still-unhealthy
   * project after it is.
   */
  private suspend fun finishByFinalHealthCheck(
      projectConnection: UnpProjectConnection,
      delegationsMade: Int,
      physicalWorkspace: PhwWorkspace,
  ): TaskCompletionResult =
      when (val healthStatus = verifyFinalHealth(projectConnection = projectConnection)) {
        ProjectHealthStatus.Healthy ->
            TaskCompletionResult.Success(
                temporaryWorkspace =
                    HrsPhysicalTemporaryWorkspace(
                        physicalWorkspace = physicalWorkspace,
                    ),
            )

        is ProjectHealthStatus.Unhealthy ->
            TaskCompletionResult.Failure.AttemptsExhausted(
                attemptsMade = delegationsMade,
                lastHealthStatus = healthStatus,
            )
      }

  private suspend fun verifyFinalHealth(
      projectConnection: UnpProjectConnection,
  ): ProjectHealthStatus {
    projectConnection.normalizeAll()

    val analyzeResult = projectConnection.analyzeAll()

    if (analyzeResult is JointResult.Failure) {
      return ProjectHealthStatus.Unhealthy(
          failureReport =
              ProjectFailureReport(
                  stage = ProjectFailureReport.Stage.Analysis,
                  failure = analyzeResult,
              ),
      )
    }

    val testResult = projectConnection.testAll()

    if (testResult is JointResult.Failure) {
      return ProjectHealthStatus.Unhealthy(
          failureReport =
              ProjectFailureReport(
                  stage = ProjectFailureReport.Stage.Testing,
                  failure = testResult,
              ),
      )
    }

    return ProjectHealthStatus.Healthy
  }
}
