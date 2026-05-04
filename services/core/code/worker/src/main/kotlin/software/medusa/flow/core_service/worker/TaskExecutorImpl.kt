package software.medusa.flow.core_service.worker

import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.session.Task
import software.medusa.git.GitEngineRepository
import software.medusa.opencode_enclosed.EnclosedModelRef
import software.medusa.opencode_enclosed.EnclosedOpencodeSessionStarter

class TaskExecutorImpl(
    private val opencodeSessionStarter: EnclosedOpencodeSessionStarter,
    private val gitRepository: GitEngineRepository,
) : TaskExecutor {
  companion object {
    private val logger = LoggerFactory.getLogger(TaskExecutor::class.java)
  }

  override suspend fun executeTask(
      sourceExecutionResults: List<TaskExecutor.TaskExecutionResult>,
      taskDefinition: Task.Definition,
      progressUpdater: TaskExecutor.TaskExecutionProgressUpdater,
  ): TaskExecutor.TaskExecutionResult {
    val opencodeSession =
        opencodeSessionStarter.startSession(
            title = "Session for task '${taskDefinition.label}'",
            workingDirectoryPath = gitRepository.path,
        )

    opencodeSession.sendMessage(
        model = EnclosedModelRef.GithubCopilot.Gpt5_4,
        text = taskDefinition.description,
    )

    val commitHash =
        gitRepository
            .commit(
                message = "Task '${taskDefinition.label}'",
            )
            .commitHash

    progressUpdater.updateProgress(1.0)

    logger.info(
        "Executed fake task label='{}' description='{}' commitHash='{}'",
        taskDefinition.label,
        taskDefinition.description,
        commitHash,
    )

    return TaskExecutor.TaskExecutionResult(
        result = 0,
    )
  }
}
