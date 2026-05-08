package software.medusa.flow.core_service.worker

import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.flows.FeatureTaskResult
import software.medusa.flow.core_service.flows.MergeTaskResult
import software.medusa.flow.core_service.flows.Task
import software.medusa.flow.core_service.flows.TaskId
import software.medusa.flow.core_service.worker.FlowExecutor.BaselineContext
import software.medusa.flow.core_service.worker.FlowExecutor.EnvironmentContext
import software.medusa.git.GitCommitDetails
import software.medusa.git.GitCommitHash
import software.medusa.git.GitPersonalDetails
import software.medusa.git.GitRefPath
import software.medusa.git.GitRepository
import software.medusa.git.GitRepository.Companion.checkIn
import software.medusa.git.GitRepository.Companion.checkOut
import software.medusa.git.GitRepository.Companion.createCommitRef
import software.medusa.git.GitRepository.Companion.createMergeCommit
import software.medusa.git.GitRepository.Companion.resolveHead
import software.medusa.opencode_enclosed.EnclosedModelRef
import software.medusa.opencode_enclosed.EnclosedOpencodeSessionStarter

class ProperFlowExecutor(
    private val gitRepository: GitRepository, // For now, the Git repo reference is baked in
    private val gitPersonalDetails: GitPersonalDetails,
    private val opencodeSessionStarter: EnclosedOpencodeSessionStarter,
) : FlowExecutor {
  companion object {
    private val logger = LoggerFactory.getLogger(FlowExecutor::class.java)
  }

  context(environmentContext: EnvironmentContext)
  override suspend fun initializeFlow(): BaselineContext {
    val rootCommitHash = gitRepository.process { resolveHead() }

    logger.debug("Initialized flow with root commit hash '{}'", rootCommitHash.raw)

    return object : BaselineContext {
      override val rootCommitHash = rootCommitHash
    }
  }

  context(environmentContext: EnvironmentContext, baselineContext: BaselineContext)
  override suspend fun executeFeatureTask(
      taskId: TaskId,
      taskDefinition: Task.FeatureDefinition,
      inputCommitHash: GitCommitHash?,
  ): FeatureTaskResult {
    // A feature task without input nodes implicitly depends on the root
    val baseCommitHash = inputCommitHash ?: baselineContext.rootCommitHash

    val taskCommitHash =
        gitRepository.processInTempWorktree(
            baseCommitHash = baseCommitHash,
            commitDetails =
                GitCommitDetails(
                    authorDetails = gitPersonalDetails,
                    committerDetails = gitPersonalDetails,
                    message = "Task '${taskDefinition.label}'",
                ),
        ) { tempWorkdirPath ->
          logger.debug(
              "Processing feature task '{}' in temporary worktree at {} with base commit {}",
              taskDefinition.label,
              tempWorkdirPath,
              baseCommitHash,
          )

          val opencodeSession =
              opencodeSessionStarter.startSession(
                  title = "FlowBlueprint for task '${taskDefinition.label}'",
                  workingDirectoryPath = tempWorkdirPath,
              )

          logger.debug(
              "Started OpenCode session for task '{}' in temporary worktree at {}",
              taskDefinition.label,
              tempWorkdirPath,
          )

          opencodeSession.sendMessage(
              model = EnclosedModelRef.GithubCopilot.Gpt5_4,
              text = taskDefinition.description,
          )

          logger.debug(
              "OpenCode agent processed task '{}' in temporary worktree at {}",
              taskDefinition.label,
              tempWorkdirPath,
          )
        }

    val taskRef = gitRepository.process {
      createCommitRef(
          commitHash = taskCommitHash,
          newRefPath = GitRefPath.of("refs", "flow", "tasks", taskId.raw),
      )
    }

    environmentContext.taskProgressSaver.updateTaskProgress(
        taskId = taskId,
        progress = 1.0,
    )

    logger.info(
        "Completed feature task label='{}' description='{}' commitHash='{}' ref='{}'",
        taskDefinition.label,
        taskDefinition.description,
        taskCommitHash.raw,
        taskRef.path.toRefString(),
    )

    return FeatureTaskResult(
        featureCommitHash = taskCommitHash,
    )
  }

  context(environmentContext: EnvironmentContext, baselineContext: BaselineContext)
  override suspend fun executeMergeTask(
      taskId: TaskId,
      baseCommitHashes: Set<GitCommitHash>,
  ): MergeTaskResult {
    require(baseCommitHashes.size >= 2) { "Merge task requires at least two parent commits" }

    val mergeCommitHash: GitCommitHash = gitRepository.process {
      createMergeCommit(
          parentCommitHashes = baseCommitHashes,
          personalDetails = gitPersonalDetails,
      )
    }

    environmentContext.taskProgressSaver.updateTaskProgress(
        taskId = taskId,
        progress = 1.0,
    )

    return MergeTaskResult(
        mergeCommitHash = mergeCommitHash,
    )
  }
}

private fun <T> GitRepository.processInTempWorktree(
    baseCommitHash: GitCommitHash,
    commitDetails: GitCommitDetails,
    block: (worktreePath: Path) -> T,
): GitCommitHash {
  val tempWorkdirPath = Files.createTempDirectory("worktree-")

  process {
    checkOut(
        sourceCommitId = baseCommitHash,
        targetWorktreePath = tempWorkdirPath,
    )
  }

  block(tempWorkdirPath)

  return process {
    checkIn(
        parentCommitId = baseCommitHash,
        sourceWorktreePath = tempWorkdirPath,
        commitDetails = commitDetails,
    )
  }
}
