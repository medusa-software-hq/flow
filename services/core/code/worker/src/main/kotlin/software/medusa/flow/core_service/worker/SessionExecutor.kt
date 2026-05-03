package software.medusa.flow.core_service.worker

import software.medusa.flow.core_service.session.SessionExecutionService
import software.medusa.flow.core_service.session.TaskGraph

class WorkingSessionExecutor(
    private val taskExecutor: TaskExecutor,
) : SessionExecutionService.SessionExecutor {
  override suspend fun execute(
      taskGraph: TaskGraph,
      progressUpdater: SessionExecutionService.SessionExecutionProgressUpdater,
  ) {
    TaskGraphExecutionContext.executeTaskGraph(
        taskGraph = taskGraph,
        taskExecutor = taskExecutor,
        progressUpdater = progressUpdater,
    )
  }
}
