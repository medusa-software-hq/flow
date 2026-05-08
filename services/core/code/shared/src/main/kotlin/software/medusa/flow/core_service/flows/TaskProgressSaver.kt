package software.medusa.flow.core_service.flows

interface TaskProgressSaver {
  fun updateTaskProgress(
      taskId: TaskId,
      progress: Double,
  )

  fun saveTaskResult(
      taskId: TaskId,
      result: TaskResult,
  )
}
