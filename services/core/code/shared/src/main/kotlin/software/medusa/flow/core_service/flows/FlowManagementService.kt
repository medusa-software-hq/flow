package software.medusa.flow.core_service.flows

import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.job_queue.FlowJobOffer
import software.medusa.flow.core_service.job_queue.FlowJobQueueFront
import software.medusa.flow.db.FlowDatabase

class FlowManagementService(
    private val database: FlowDatabase,
    private val flowJobQueueFront: FlowJobQueueFront,
) {
  companion object {
    private val logger = LoggerFactory.getLogger(FlowManagementService::class.java)
  }

  fun createFlow(
      flowBlueprint: FlowBlueprint,
  ): FlowId {
    logger.info("Creating draft flowBlueprint title='{}'", flowBlueprint.title)

    database.sessionQueries.insertSession(
        title = flowBlueprint.title,
        task_graph_proto_bytes = flowBlueprint.taskGraph.toProtoBytes(),
        state = FlowState.DRAFT.toDbValue(),
    )

    val createdFlowId =
        FlowId(
            raw = database.sessionQueries.lastInsertRowId().executeAsOne(),
        )

    return createdFlowId
  }

  fun getFlowById(id: FlowId): FlowDump? =
      database.sessionQueries.selectSessionById(id.raw).executeAsOneOrNull()?.toDump()

  fun updateFlow(
      id: FlowId,
      flowBlueprint: FlowBlueprint,
  ) {
    logger.info("Updating flowBlueprint {} title='{}'", id, flowBlueprint.title)

    val existingFlowBlueprint = getFlowById(id) ?: error("FlowBlueprint $id not found")
    require(existingFlowBlueprint.state == FlowState.DRAFT) { "FlowBlueprint $id is not editable" }

    database.sessionQueries.updateSessionDetails(
        title = flowBlueprint.title,
        task_graph_proto_bytes = flowBlueprint.taskGraph.toProtoBytes(),
        id = id.raw,
    )
  }

  fun triggerFlowRun(
      id: FlowId,
      finalFlowBlueprint: FlowBlueprint,
  ) {
    logger.info("Queueing flow {} for running", id)

    val existingFlowBlueprint = getFlowById(id) ?: error("FlowBlueprint $id not found")

    require(existingFlowBlueprint.state == FlowState.DRAFT) { "FlowBlueprint $id is not startable" }

    database.sessionQueries.transaction {
      database.sessionQueries.updateSessionDetails(
          title = finalFlowBlueprint.title,
          task_graph_proto_bytes = finalFlowBlueprint.taskGraph.toProtoBytes(),
          id = id.raw,
      )
      database.sessionQueries.updateSessionState(
          state = FlowState.RUNNING.toDbValue(),
          id = id.raw,
      )

      finalFlowBlueprint.taskGraph.tasks.forEach { task ->
        database.sessionQueries.upsertSessionTaskExecutionState(
            session_id = id.raw,
            task_id = task.id.raw,
            progress = 0.0,
            result_int = null,
        )
      }
    }

    flowJobQueueFront.offerJob(
        FlowJobOffer(
            flowId = id,
        ),
    )
  }

  fun getAllFlows(): List<FlowDump> {
    return database.sessionQueries.selectAllSessions().executeAsList().map { it.toDump() }
  }
}
