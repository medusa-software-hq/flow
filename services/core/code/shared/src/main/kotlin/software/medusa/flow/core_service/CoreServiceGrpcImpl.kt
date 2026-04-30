package software.medusa.flow.core_service

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import software.medusa.grpc.flow.core_service.v1.CheckTaskGraphRequest
import software.medusa.grpc.flow.core_service.v1.CheckTaskGraphResponse
import software.medusa.grpc.flow.core_service.v1.CoreServiceGrpcKt
import software.medusa.grpc.flow.core_service.v1.StartSessionRequest
import software.medusa.grpc.flow.core_service.v1.StartSessionResponse
import software.medusa.grpc.flow.core_service.v1.checkTaskGraphResponse
import software.medusa.grpc.flow.core_service.v1.sessionStartedResult
import software.medusa.grpc.flow.core_service.v1.startSessionResponse
import software.medusa.grpc.flow.core_service.v1.taskGraphValidResult

class CoreServiceGrpcImpl(
    private val coroutineDispatcher: CoroutineDispatcher,
    private val sessionManagementService: SessionManagementService,
) : CoreServiceGrpcKt.CoreServiceCoroutineImplBase() {
  override val context: CoroutineContext
    get() = coroutineDispatcher

  override suspend fun checkTaskGraph(
      request: CheckTaskGraphRequest,
  ): CheckTaskGraphResponse {
    if (!request.hasTaskGraph() || !isTaskGraphValid(request.taskGraph)) {
      return checkTaskGraphValidationFailed()
    }

    return checkTaskGraphResponse { valid = taskGraphValidResult {} }
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
}
