package software.medusa.flow.core_service.worker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.session.SessionExecutionService.SessionExecutionProgressUpdater
import software.medusa.flow.core_service.session.SessionExecutionService.TaskExecutionResult as SessionTaskExecutionResult
import software.medusa.flow.core_service.session.Task
import software.medusa.flow.core_service.session.TaskGraph
import software.medusa.flow.core_service.session.TaskId
import software.medusa.flow.core_service.worker.TaskExecutor.TaskExecutionResult

class TaskGraphExecutionContext
private constructor(
    private val taskGraph: TaskGraph,
    private val taskExecutor: TaskExecutor,
    private val progressUpdater: SessionExecutionProgressUpdater,
    private val coroutineScope: CoroutineScope,
) {
  companion object {
    private val logger = LoggerFactory.getLogger(TaskGraphExecutionContext::class.java)

    suspend fun executeTaskGraph(
        taskGraph: TaskGraph,
        taskExecutor: TaskExecutor,
        progressUpdater: SessionExecutionProgressUpdater,
    ) {
      coroutineScope {
        TaskGraphExecutionContext(
                taskGraph = taskGraph,
                taskExecutor = taskExecutor,
                progressUpdater = progressUpdater,
                coroutineScope = this,
            )
            .executeAll()
      }
    }
  }

  private val executedTaskById = mutableMapOf<TaskId, Deferred<TaskExecutionResult>>()

  private suspend fun executeAll() {
    logger.info("Executing task graph with {} tasks", taskGraph.tasks.size)
    taskGraph.tasks.map { ensureTaskIsBeingExecuted(it) }.awaitAll()
    logger.info("Finished task graph execution")
  }

  fun ensureTaskIsBeingExecuted(
      task: Task,
  ): Deferred<TaskExecutionResult> =
      executedTaskById.getOrPut(task.id) {
        logger.debug("Scheduling task {} ({})", task.id, task.definition.label)

        beginTaskExecution(task = task)
      }

  private fun beginTaskExecution(
      task: Task,
  ): Deferred<TaskExecutionResult> {
    val sourceTasks = taskGraph.getSourceTasks(targetTask = task)

    logger.debug(
        "Beginning execution for task {} ({}) with sourceTaskIds={}",
        task.id,
        task.definition.label,
        sourceTasks.map { it.id },
    )

    val sourceDeferredExecutionResults: List<Deferred<TaskExecutionResult>> =
        sourceTasks.map { sourceTask ->
          ensureTaskIsBeingExecuted(task = sourceTask)
        }

    return coroutineScope.async {
      val sourceExecutionResults: List<TaskExecutionResult> =
          sourceDeferredExecutionResults.awaitAll()

      logger.debug(
          "Task {} dependencies resolved with {} source results",
          task.id,
          sourceExecutionResults.size,
      )

      val result =
          taskExecutor.executeTask(
              sourceExecutionResults = sourceExecutionResults,
              taskDefinition = task.definition,
              progressUpdater =
                  object : TaskExecutor.TaskExecutionProgressUpdater {
                    override fun updateProgress(progress: Double) {
                      logger.debug("Task {} progress {}", task.id, progress)
                      progressUpdater.updateTaskProgress(
                          taskId = task.id,
                          progress = progress,
                      )
                    }
                  },
          )

      progressUpdater.updateTaskResult(
          taskId = task.id,
          result = SessionTaskExecutionResult(resultInt = result.result),
      )

      logger.info("Task {} completed with result {}", task.id, result.result)

      result
    }
  }
}
