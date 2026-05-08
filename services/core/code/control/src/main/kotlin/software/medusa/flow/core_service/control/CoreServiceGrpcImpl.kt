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
import software.medusa.flow.core_service.flows.toPbRunningSessionProgress
import software.medusa.flow.core_service.flows.toPbSessionDetails
import software.medusa.flow.core_service.flows.toPbSessionDump
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCheckTaskGraphRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCheckTaskGraphResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCreateSessionRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCreateSessionResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceGetRunningSessionProgressRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceGetRunningSessionProgressResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceGrpcKt
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceListSessionsRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceListSessionsResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceStartSessionRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceStartSessionResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceUpdateSessionRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceUpdateSessionResponse
import software.medusa.grpc.flow.control_service.v1.PbTaskGraph
import software.medusa.grpc.flow.control_service.v1.detailsOrNull
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceCheckTaskGraphResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceCreateSessionResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceGetRunningSessionProgressResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceListSessionsResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceStartSessionResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceUpdateSessionResponse
import software.medusa.grpc.flow.control_service.v1.pbSessionStartedResult
import software.medusa.grpc.flow.control_service.v1.pbTaskGraphValidResult
import software.medusa.grpc.flow.control_service.v1.pbTaskGraphValidationFailedStatus
import software.medusa.grpc.flow.control_service.v1.taskGraphOrNull

class CoreServiceGrpcImpl(
    private val coroutineDispatcher: CoroutineDispatcher,
    private val sessionControlService: FlowManagementService,
    private val runningFlowProgressProvider: RunningFlowProgressProvider,
) : GrpcControlServiceGrpcKt.GrpcControlServiceCoroutineImplBase() {
  override val context: CoroutineContext
    get() = coroutineDispatcher

  override suspend fun listSessions(
      request: GrpcControlServiceListSessionsRequest,
  ): GrpcControlServiceListSessionsResponse {
    val sessions = sessionControlService.getAllSessions()

    return grpcControlServiceListSessionsResponse {
      this.sessions += sessions.map { it.toPbSessionDump() }
    }
  }

  override suspend fun createSession(
      request: GrpcControlServiceCreateSessionRequest,
  ): GrpcControlServiceCreateSessionResponse {
    val createdSession = request.details.toModel()

    val createdSessionId =
        sessionControlService.createSession(
            flowBlueprint = createdSession,
        )

    return grpcControlServiceCreateSessionResponse { sessionId = createdSessionId.raw.toString() }
  }

  override suspend fun updateSession(
      request: GrpcControlServiceUpdateSessionRequest,
  ): GrpcControlServiceUpdateSessionResponse {
    val rawSessionId =
        request.sessionId.ifBlank {
          throw statusException(Status.INVALID_ARGUMENT, "session_id is required")
        }

    val sessionId = parseSessionId(rawSessionId)

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

    sessionControlService.updateSession(
        id = sessionId,
        flowBlueprint = updatedFlowBlueprint,
    )

    return grpcControlServiceUpdateSessionResponse {}
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

  override suspend fun startSession(
      request: GrpcControlServiceStartSessionRequest,
  ): GrpcControlServiceStartSessionResponse {
    val rawSessionId =
        request.sessionId.ifBlank {
          throw statusException(Status.INVALID_ARGUMENT, "session_id is required")
        }

    val sessionId = parseSessionId(rawSessionId)

    val finalSession = request.finalDetails.toModel()

    sessionControlService.triggerFlowRun(
        id = sessionId,
        finalFlowBlueprint = finalSession,
    )

    return grpcControlServiceStartSessionResponse {
      started = pbSessionStartedResult { startedSessionDetails = finalSession.toPbSessionDetails() }
    }
  }

  override suspend fun getRunningSessionProgress(
      request: GrpcControlServiceGetRunningSessionProgressRequest,
  ): GrpcControlServiceGetRunningSessionProgressResponse {
    val rawSessionId = request.sessionId

    if (rawSessionId.isBlank()) {
      throw statusException(Status.INVALID_ARGUMENT, "session_id is required")
    }

    val sessionId = parseSessionId(rawSessionId)

    val runningSessionProgress =
        runningFlowProgressProvider.getRunningFlowProgress(flowId = sessionId)
            ?: throw statusException(Status.NOT_FOUND, "flow not found")

    return grpcControlServiceGetRunningSessionProgressResponse {
      this.runningSessionProgress = runningSessionProgress.toPbRunningSessionProgress()
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

  private fun parseSessionId(rawSessionId: String): FlowId {
    val numericSessionId =
        rawSessionId.toLongOrNull()
            ?: throw statusException(Status.INVALID_ARGUMENT, "session_id must be numeric")

    return FlowId(raw = numericSessionId)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(CoreServiceGrpcImpl::class.java)
  }
}
