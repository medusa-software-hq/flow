package software.medusa.flow.core_service.control

import io.grpc.Status
import io.grpc.StatusException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import software.medusa.flow.core_service.session.SessionManagementService
import software.medusa.flow.core_service.session.toModel
import software.medusa.flow.core_service.session.toPbSessionSummary
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCheckTaskGraphRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceCheckTaskGraphResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceGrpcKt
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceListSessionsRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceListSessionsResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceStartSessionRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceStartSessionResponse
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceUpdateSessionRequest
import software.medusa.grpc.flow.control_service.v1.GrpcControlServiceUpdateSessionResponse
import software.medusa.grpc.flow.control_service.v1.PbTaskGraph
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceCheckTaskGraphResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceListSessionsResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceStartSessionResponse
import software.medusa.grpc.flow.control_service.v1.grpcControlServiceUpdateSessionResponse
import software.medusa.grpc.flow.control_service.v1.pbSessionStartedResult
import software.medusa.grpc.flow.control_service.v1.pbTaskGraphValidResult
import software.medusa.grpc.flow.control_service.v1.pbTaskGraphValidationFailedStatus

class CoreServiceGrpcImpl(
    private val coroutineDispatcher: CoroutineDispatcher,
    private val sessionControlService: SessionManagementService,
) : GrpcControlServiceGrpcKt.GrpcControlServiceCoroutineImplBase() {
  override val context: CoroutineContext
    get() = coroutineDispatcher

  override suspend fun listSessions(
      request: GrpcControlServiceListSessionsRequest,
  ): GrpcControlServiceListSessionsResponse {
    val sessions = sessionControlService.getAllSessions()

    return grpcControlServiceListSessionsResponse {
      this.sessions += sessions.map { it.toPbSessionSummary() }
    }
  }

  override suspend fun checkTaskGraph(
      request: GrpcControlServiceCheckTaskGraphRequest,
  ): GrpcControlServiceCheckTaskGraphResponse {
    if (!request.hasTaskGraph() || !isTaskGraphValid(request.taskGraph)) {
      return checkTaskGraphValidationFailed()
    }

    return grpcControlServiceCheckTaskGraphResponse { valid = pbTaskGraphValidResult {} }
  }

  override suspend fun updateSession(
      request: GrpcControlServiceUpdateSessionRequest,
  ): GrpcControlServiceUpdateSessionResponse {
    if (request.sessionId.isBlank()) {
      throw statusException(Status.INVALID_ARGUMENT, "session_id is required")
    }

    if (!request.hasTaskGraph() || !isTaskGraphValid(request.taskGraph)) {
      throw statusException(Status.INVALID_ARGUMENT, "task_graph is invalid")
    }

    val updatedSession =
        sessionControlService.updateSession(
            id = request.sessionId,
            title = request.title,
            taskGraph = request.taskGraph.toModel(),
        )

    if (updatedSession == null) {
      throw statusException(Status.NOT_FOUND, "session not found")
    }

    return grpcControlServiceUpdateSessionResponse {}
  }

  override suspend fun startSession(
      request: GrpcControlServiceStartSessionRequest,
  ): GrpcControlServiceStartSessionResponse {
    if (!request.hasTaskGraph() || !isTaskGraphValid(request.taskGraph)) {
      return startSessionValidationFailed()
    }

    val session =
        sessionControlService.createSession(
            title = request.title,
            taskGraph = request.taskGraph.toModel(),
        )

    return grpcControlServiceStartSessionResponse {
      started = pbSessionStartedResult { sessionId = session.id }
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

  private fun startSessionValidationFailed(): GrpcControlServiceStartSessionResponse {
    return grpcControlServiceStartSessionResponse {
      validationFailed = pbTaskGraphValidationFailedStatus {}
    }
  }

  private fun statusException(status: Status, description: String): StatusException {
    return status.withDescription(description).asException()
  }
}
