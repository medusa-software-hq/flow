package software.medusa.flow.core_service.session

import org.slf4j.LoggerFactory
import software.medusa.flow.db.FlowDatabase

class SessionExecutionService(
    private val database: FlowDatabase,
) {
  data class TaskExecutionResult(
      val resultInt: Int,
  )

  interface SessionExecutionProgressUpdater {
    fun updateTaskProgress(
        taskId: TaskId,
        progress: Double,
    )

    fun updateTaskResult(
        taskId: TaskId,
        result: TaskExecutionResult,
    )
  }

  interface SessionExecutor {
    suspend fun execute(
        taskGraph: TaskGraph,
        progressUpdater: SessionExecutionProgressUpdater,
    )
  }

  companion object {
    private val logger = LoggerFactory.getLogger(SessionExecutionService::class.java)
  }

  suspend fun leaseSessionForExecution(
      sessionId: SessionId,
      sessionExecutor: SessionExecutor,
  ) {
    // In the near future, we'll put the actual lease acquisition logic here
    // For now we can just execute the session in YOLO mode

    val dbSession =
        database.sessionQueries
            .selectSessionById(
                id = sessionId.raw,
            )
            .executeAsOneOrNull()

    if (dbSession == null) {
      logger.warn("No session {} found to execute", sessionId)

      return
    }

    logger.info("Starting execution for session {} title='{}'", sessionId, dbSession.title)

    val taskGraph = dbSession.task_graph_proto_bytes.toTaskGraphModel()

    sessionExecutor.execute(
        taskGraph = taskGraph,
        progressUpdater =
            object : SessionExecutionProgressUpdater {
              override fun updateTaskProgress(
                  taskId: TaskId,
                  progress: Double,
              ) {
                database.sessionQueries.updateSessionTaskProgress(
                    progress = progress,
                    session_id = sessionId.raw,
                    task_id = taskId.raw,
                )
              }

              override fun updateTaskResult(
                  taskId: TaskId,
                  result: TaskExecutionResult,
              ) {
                database.sessionQueries.updateSessionTaskResult(
                    result_int = result.resultInt.toLong(),
                    session_id = sessionId.raw,
                    task_id = taskId.raw,
                )
              }
            },
    )

    logger.info("Finished execution for session {}", sessionId)
  }

  fun getRunningSessionProgress(sessionId: SessionId): RunningSessionProgress? {
    val session =
        database.sessionQueries.selectSessionById(sessionId.raw).executeAsOneOrNull() ?: return null

    if (session.state.toModel() != SessionState.RUNNING) {
      return null
    }

    return RunningSessionProgress(
        taskExecutionProgresses =
            database.sessionQueries
                .selectSessionTaskExecutionStatesBySessionId(sessionId.raw)
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
