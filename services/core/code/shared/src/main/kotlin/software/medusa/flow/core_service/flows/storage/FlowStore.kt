package software.medusa.flow.core_service.flows.storage

import software.medusa.flow.core_service.flows.FlowBlueprint
import software.medusa.flow.core_service.flows.FlowId
import software.medusa.flow.core_service.flows.FlowState
import software.medusa.flow.core_service.flows.RunningFlowProgress
import software.medusa.flow.core_service.flows.RunningFlowProgressProvider
import software.medusa.flow.core_service.flows.TaskExecutionProgress
import software.medusa.flow.core_service.flows.TaskId
import software.medusa.flow.core_service.flows.TaskProgressSaver
import software.medusa.flow.core_service.flows.TaskResult
import software.medusa.flow.core_service.flows.TaskResultRestorer
import software.medusa.flow.core_service.flows.toModel
import software.medusa.flow.db.FlowDatabase

class FlowStore(
    private val flowDatabase: FlowDatabase,
) : RunningFlowProgressProvider {
  interface LeasedFlowStore : TaskResultRestorer, TaskProgressSaver

  interface LeasedFlowProcessor {
    suspend fun processLeasedFlow(
        flowBlueprint: FlowBlueprint,
        leasedFlowStore: LeasedFlowStore,
    )
  }

  enum class LeaseFlowResult {
    Processed,
    Denied,
  }

  suspend fun leaseFlow(
      flowId: FlowId,
      processor: LeasedFlowProcessor,
  ): LeaseFlowResult {
    // In the near future, we'll put the actual lease acquisition logic here
    // For now we can just execute the flow in YOLO mode

    val dbSession =
        flowDatabase.sessionQueries
            .selectSessionById(
                id = flowId.raw,
            )
            .executeAsOneOrNull()

    if (dbSession == null) {
      throw IllegalArgumentException("FlowBlueprint with ID ${flowId.raw} not found")
    }

    val session = dbSession.toModel()

    val leasedFlowStore =
        object : LeasedFlowStore {
          override fun restoreTaskResult(
              taskId: TaskId,
          ): TaskResult? {
            // TODO: Actually restore task results
            return null
          }

          override fun updateTaskProgress(taskId: TaskId, progress: Double) {
            flowDatabase.sessionQueries.updateSessionTaskProgress(
                progress = progress,
                session_id = flowId.raw,
                task_id = taskId.raw,
            )
          }

          override fun saveTaskResult(
              taskId: TaskId,
              result: TaskResult,
          ) {
            // TODO: Actually save task results
          }
        }

    processor.processLeasedFlow(
        flowBlueprint = session,
        leasedFlowStore = leasedFlowStore,
    )

    return LeaseFlowResult.Processed
  }

  override fun getRunningFlowProgress(
      flowId: FlowId,
  ): RunningFlowProgress? {
    val session =
        flowDatabase.sessionQueries.selectSessionById(flowId.raw).executeAsOneOrNull()
            ?: return null

    if (session.state.toModel() != FlowState.RUNNING) {
      return null
    }

    return RunningFlowProgress(
        taskExecutionProgresses =
            flowDatabase.sessionQueries
                .selectSessionTaskExecutionStatesBySessionId(flowId.raw)
                .executeAsList()
                .map { state ->
                  TaskExecutionProgress(
                      taskId = TaskId(raw = state.task_id),
                      progress = state.progress,
                  )
                },
    )
  }
}
