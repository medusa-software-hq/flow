package software.medusa.flow.core_service.worker

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.session.Task

class TaskExecutor {
  data class TaskExecutionResult(
      val result: Int,
  )

  interface TaskExecutionProgressUpdater {
    fun updateProgress(progress: Double)
  }

  suspend fun executeTask(
      sourceExecutionResults: List<TaskExecutionResult>,
      taskDefinition: Task.Definition,
      progressUpdater: TaskExecutionProgressUpdater,
  ): TaskExecutionResult {
    delay(2.seconds)

    progressUpdater.updateProgress(0.25)

    delay(2.seconds)

    progressUpdater.updateProgress(0.5)

    delay(2.seconds)

    progressUpdater.updateProgress(0.75)

    delay(2.seconds)

    progressUpdater.updateProgress(1.0)

    val combinedResult: Int =
        when {
          taskDefinition.description.startsWith("Add") -> sourceExecutionResults.sumOf { it.result }

          taskDefinition.description.startsWith("Multiply") ->
              sourceExecutionResults.fold(1) { acc, result -> acc * result.result }

          else -> 0
        }

    val result = combinedResult + 1

    logger.info(
        "Executed fake task label='{}' description='{}' sourceCount={} result={}",
        taskDefinition.label,
        taskDefinition.description,
        sourceExecutionResults.size,
        result,
    )

    return TaskExecutionResult(
        result = result,
    )
  }

  companion object {
    private val logger = LoggerFactory.getLogger(TaskExecutor::class.java)
  }
}
