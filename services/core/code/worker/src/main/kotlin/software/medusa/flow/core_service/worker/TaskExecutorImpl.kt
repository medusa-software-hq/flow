package software.medusa.flow.core_service.worker

import java.nio.file.Path
import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.session.Task
import software.medusa.opencode_enclosed.EnclosedModelRef
import software.medusa.opencode_enclosed.EnclosedOpencodeSessionStarter

class TaskExecutorImpl(
    private val opencodeSessionStarter: EnclosedOpencodeSessionStarter,
    private val workingDirectoryPath: Path,
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
            workingDirectoryPath = workingDirectoryPath,
        )

    opencodeSession.sendMessage(
        model = EnclosedModelRef.GithubCopilot.Gpt5_4,
        text = taskDefinition.description,
    )

    progressUpdater.updateProgress(1.0)

    logger.info(
        "Executed fake task label='{}' description='{}'",
        taskDefinition.label,
        taskDefinition.description,
    )

    return TaskExecutor.TaskExecutionResult(
        result = 0,
    )
  }
}
