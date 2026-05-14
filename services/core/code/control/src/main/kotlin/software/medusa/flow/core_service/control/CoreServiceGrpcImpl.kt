package software.medusa.flow.core_service.control

import io.grpc.Status
import io.grpc.StatusException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.flows.FlowBlueprint
import software.medusa.flow.core_service.flows.FlowId
import software.medusa.flow.core_service.flows.FlowManagementService
import software.medusa.flow.core_service.flows.RunningFlowProgressProvider
import software.medusa.flow.core_service.flows.toModel
import software.medusa.flow.core_service.flows.toPbFlowDetails
import software.medusa.flow.core_service.flows.toPbFlowDump
import software.medusa.flow.core_service.flows.toPbRunningFlowProgress
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCheckTaskGraphRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCheckTaskGraphResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCreateFlowRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCreateFlowResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceGetRunningFlowProgressRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceGetRunningFlowProgressResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceGrpcKt
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceListFlowsRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceListFlowsResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceStartFlowRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceStartFlowResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceUpdateFlowRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceUpdateFlowResponse
import software.medusa.grpc.flow.control_service.v1.PbTaskGraph
import software.medusa.grpc.flow.control_service.v1.detailsOrNull
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceCheckTaskGraphResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceCreateFlowResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceGetRunningFlowProgressResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceListFlowsResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceStartFlowResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceUpdateFlowResponse
import software.medusa.grpc.flow.control_service.v1.pbFlowStartedResult
import software.medusa.grpc.flow.control_service.v1.pbTaskGraphValidResult
import software.medusa.grpc.flow.control_service.v1.pbTaskGraphValidationFailedStatus
import software.medusa.grpc.flow.control_service.v1.taskGraphOrNull

class CoreServiceGrpcImpl(
    private val coroutineDispatcher: CoroutineDispatcher,
    private val flowManagementService: FlowManagementService,
    private val runningFlowProgressProvider: RunningFlowProgressProvider,
) : GrpcControlServiceGrpcKt.GrpcControlServiceCoroutineImplBase() {
  override val context: CoroutineContext
    get() = coroutineDispatcher

  override suspend fun listFlows(
      request: GrpcControlServiceListFlowsRequest,
  ): GrpcControlServiceListFlowsResponse {
    val flows = flowManagementService.getAllFlows()

    return grpcControlServiceListFlowsResponse { this.flows += flows.map { it.toPbFlowDump() } }
  }

  override suspend fun createFlow(
      request: GrpcControlServiceCreateFlowRequest,
  ): GrpcControlServiceCreateFlowResponse {
    val createdFlow = request.details.toModel()

    val createdFlowId =
        flowManagementService.createFlow(
            flowBlueprint = createdFlow,
        )

    return grpcControlServiceCreateFlowResponse { flowId = createdFlowId.raw.toString() }
  }

  override suspend fun updateFlow(
      request: GrpcControlServiceUpdateFlowRequest,
  ): GrpcControlServiceUpdateFlowResponse {
    val rawFlowId =
        request.flowId.ifBlank {
          throw statusException(Status.INVALID_ARGUMENT, "flow_id is required")
        }

    val flowId = parseFlowId(rawFlowId)

    val details =
        request.detailsOrNull
            ?: throw statusException(
                Status.INVALID_ARGUMENT,
                "details is required",
            )

    val rawTaskGraph =
        details.taskGraphOrNull
            ?: throw statusException(
                Status.INVALID_ARGUMENT,
                "task_graph is required",
            )

    val taskGraph = rawTaskGraph.toModel()

    val updatedFlowBlueprint =
        FlowBlueprint(
            title = details.title,
            taskGraph = taskGraph,
        )

    flowManagementService.updateFlow(
        id = flowId,
        flowBlueprint = updatedFlowBlueprint,
    )

    return grpcControlServiceUpdateFlowResponse {}
  }

  override suspend fun checkTaskGraph(
      request: GrpcControlServiceCheckTaskGraphRequest,
  ): GrpcControlServiceCheckTaskGraphResponse {
    if (!request.hasTaskGraph() || !isTaskGraphValid(request.taskGraph)) {
      logger.warn("Rejecting task graph as invalid")

      return checkTaskGraphValidationFailed()
    }

    return grpcControlServiceCheckTaskGraphResponse { valid = pbTaskGraphValidResult {} }
  }

  override suspend fun startFlow(
      request: GrpcControlServiceStartFlowRequest,
  ): GrpcControlServiceStartFlowResponse {
    val rawFlowId =
        request.flowId.ifBlank {
          throw statusException(Status.INVALID_ARGUMENT, "flow_id is required")
        }

    val flowId = parseFlowId(rawFlowId)

    val finalFlow = request.finalDetails.toModel()

    flowManagementService.triggerFlowRun(
        id = flowId,
        finalFlowBlueprint = finalFlow,
    )

    return grpcControlServiceStartFlowResponse {
      started = pbFlowStartedResult { startedFlowDetails = finalFlow.toPbFlowDetails() }
    }
  }

  override suspend fun getRunningFlowProgress(
      request: GrpcControlServiceGetRunningFlowProgressRequest,
  ): GrpcControlServiceGetRunningFlowProgressResponse {
    val rawFlowId = request.flowId

    if (rawFlowId.isBlank()) {
      throw statusException(Status.INVALID_ARGUMENT, "flow_id is required")
    }

    val flowId = parseFlowId(rawFlowId)

    val runningFlowProgress =
        runningFlowProgressProvider.getRunningFlowProgress(flowId = flowId)
            ?: throw statusException(Status.NOT_FOUND, "flow not found")

    return grpcControlServiceGetRunningFlowProgressResponse {
      this.runningFlowProgress = runningFlowProgress.toPbRunningFlowProgress()
    }
  }

  private fun isTaskGraphValid(taskGraph: PbTaskGraph): Boolean {
    val taskIds = taskGraph.tasksList.mapTo(mutableSetOf()) { it.id }

    if (taskIds.size != taskGraph.tasksCount) {
      return false
    }

    return taskGraph.tasksList.all { task -> task.sourceTaskIdsList.all(taskIds::contains) }
  }

  private fun checkTaskGraphValidationFailed(): GrpcControlServiceCheckTaskGraphResponse {
    return grpcControlServiceCheckTaskGraphResponse {
      validationFailed = pbTaskGraphValidationFailedStatus {}
    }
  }

  private fun statusException(status: Status, description: String): StatusException =
      status.withDescription(description).asException()

  private fun parseFlowId(rawFlowId: String): FlowId {
    val numericFlowId =
        rawFlowId.toLongOrNull()
            ?: throw statusException(Status.INVALID_ARGUMENT, "flow_id must be numeric")

    return FlowId(raw = numericFlowId)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(CoreServiceGrpcImpl::class.java)
  }
}
