package software.medusa.flow.core_service.flows

interface TaskResultRestorer {
  fun restoreTaskResult(
      taskId: TaskId,
  ): TaskResult?
}
