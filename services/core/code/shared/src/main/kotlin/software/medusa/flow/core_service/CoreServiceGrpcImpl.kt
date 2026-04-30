package software.medusa.flow.core_service

import io.grpc.Status
import io.grpc.StatusException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import software.medusa.grpc.flow.core_service.v1.CheckTaskGraphRequest
import software.medusa.grpc.flow.core_service.v1.CheckTaskGraphResponse
import software.medusa.grpc.flow.core_service.v1.CoreServiceGrpcKt
import software.medusa.grpc.flow.core_service.v1.ListSessionsRequest
import software.medusa.grpc.flow.core_service.v1.ListSessionsResponse
import software.medusa.grpc.flow.core_service.v1.StartSessionRequest
import software.medusa.grpc.flow.core_service.v1.StartSessionResponse
import software.medusa.grpc.flow.core_service.v1.UpdateSessionRequest
import software.medusa.grpc.flow.core_service.v1.UpdateSessionResponse
import software.medusa.grpc.flow.core_service.v1.checkTaskGraphResponse
import software.medusa.grpc.flow.core_service.v1.listSessionsResponse
import software.medusa.grpc.flow.core_service.v1.sessionStartedResult
import software.medusa.grpc.flow.core_service.v1.sessionSummary
import software.medusa.grpc.flow.core_service.v1.startSessionResponse
import software.medusa.grpc.flow.core_service.v1.taskGraphValidResult
import software.medusa.grpc.flow.core_service.v1.updateSessionResponse

class CoreServiceGrpcImpl(
    private val coroutineDispatcher: CoroutineDispatcher,
    private val sessionManagementService: SessionManagementService,
) : CoreServiceGrpcKt.CoreServiceCoroutineImplBase() {
  override val context: CoroutineContext
    get() = coroutineDispatcher

  override suspend fun listSessions(
      request: ListSessionsRequest,
  ): ListSessionsResponse {
    val sessions = sessionManagementService.getAllSessions()

    return listSessionsResponse {
      this.sessions += sessions.map { session ->
        sessionSummary {
          sessionId = session.id
          taskGraph = sessionManagementService.decodeTaskGraph(session)
        }
      }
    }
  }

  override suspend fun checkTaskGraph(
      request: CheckTaskGraphRequest,
  ): CheckTaskGraphResponse {
    if (!request.hasTaskGraph() || !isTaskGraphValid(request.taskGraph)) {
      return checkTaskGraphValidationFailed()
    }

    return checkTaskGraphResponse { valid = taskGraphValidResult {} }
  }

  override suspend fun updateSession(
      request: UpdateSessionRequest,
  ): UpdateSessionResponse {
    if (request.sessionId.isBlank()) {
      throw statusException(Status.INVALID_ARGUMENT, "session_id is required")
    }

    if (!request.hasTaskGraph() || !isTaskGraphValid(request.taskGraph)) {
      throw statusException(Status.INVALID_ARGUMENT, "task_graph is invalid")
    }

    val updatedSession =
        sessionManagementService.updateSessionTaskGraph(
            id = request.sessionId,
            taskGraph = request.taskGraph,
        )

    if (updatedSession == null) {
      throw statusException(Status.NOT_FOUND, "session not found")
    }

    return updateSessionResponse {}
  }

  override suspend fun startSession(
      request: StartSessionRequest,
  ): StartSessionResponse {
    if (!request.hasTaskGraph() || !isTaskGraphValid(request.taskGraph)) {
      return startSessionValidationFailed()
    }

    val session = sessionManagementService.createSession(taskGraph = request.taskGraph)

    return startSessionResponse { started = sessionStartedResult { sessionId = session.id } }
  }

  private fun isTaskGraphValid(
      taskGraph: software.medusa.grpc.flow.core_service.v1.TaskGraph
  ): Boolean {
    val taskIds = taskGraph.tasksList.mapTo(mutableSetOf()) { it.id }

    if (taskIds.size != taskGraph.tasksCount) {
      return false
    }

    return taskGraph.tasksList.all { task -> task.sourceTaskIdsList.all(taskIds::contains) }
  }

  private fun checkTaskGraphValidationFailed(): CheckTaskGraphResponse {
    return checkTaskGraphResponse {
      validationFailed =
          software.medusa.grpc.flow.core_service.v1.taskGraphValidationFailedStatus {}
    }
  }

  private fun startSessionValidationFailed(): StartSessionResponse {
    return startSessionResponse {
      validationFailed =
          software.medusa.grpc.flow.core_service.v1.taskGraphValidationFailedStatus {}
    }
  }

  private fun statusException(status: Status, description: String): StatusException {
    return status.withDescription(description).asException()
  }
}
