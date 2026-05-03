package software.medusa.flow.core_service.worker

import software.medusa.flow.core_service.session.Task

interface TaskExecutor {
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
  ): TaskExecutionResult
}
