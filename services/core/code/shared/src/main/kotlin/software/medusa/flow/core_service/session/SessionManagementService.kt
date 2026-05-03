package software.medusa.flow.core_service.session

import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.job_queue.SessionExecutionJobOffer
import software.medusa.flow.core_service.job_queue.SessionExecutionJobQueueFront
import software.medusa.flow.db.FlowDatabase

class SessionManagementService(
    private val database: FlowDatabase,
    private val sessionExecutionJobQueueFront: SessionExecutionJobQueueFront,
) {
  companion object {
    private val logger = LoggerFactory.getLogger(SessionManagementService::class.java)
  }

  fun createSession(
      session: Session,
  ): SessionId {
    logger.info("Creating draft session title='{}'", session.title)

    database.sessionQueries.insertSession(
        title = session.title,
        task_graph_proto_bytes = session.taskGraph.toProtoBytes(),
        state = SessionState.DRAFT.toDbValue(),
    )

    val createdSessionId =
        SessionId(
            raw = database.sessionQueries.lastInsertRowId().executeAsOne(),
        )

    return createdSessionId
  }

  fun getSessionById(id: SessionId): SessionDump? =
      database.sessionQueries.selectSessionById(id.raw).executeAsOneOrNull()?.toDump()

  fun updateSession(
      id: SessionId,
      session: Session,
  ) {
    logger.info("Updating session {} title='{}'", id, session.title)

    val existingSession = getSessionById(id) ?: error("Session $id not found")
    require(existingSession.state == SessionState.DRAFT) { "Session $id is not editable" }

    database.sessionQueries.updateSessionDetails(
        title = session.title,
        task_graph_proto_bytes = session.taskGraph.toProtoBytes(),
        id = id.raw,
    )
  }

  fun startSession(
      id: SessionId,
      finalSession: Session,
  ) {
    logger.info("Queueing session {} for execution", id)

    val existingSession = getSessionById(id) ?: error("Session $id not found")
    require(existingSession.state == SessionState.DRAFT) { "Session $id is not startable" }

    database.sessionQueries.transaction {
      database.sessionQueries.updateSessionDetails(
          title = finalSession.title,
          task_graph_proto_bytes = finalSession.taskGraph.toProtoBytes(),
          id = id.raw,
      )
      database.sessionQueries.updateSessionState(
          state = SessionState.RUNNING.toDbValue(),
          id = id.raw,
      )

      finalSession.taskGraph.tasks.forEach { task ->
        database.sessionQueries.upsertSessionTaskExecutionState(
            session_id = id.raw,
            task_id = task.id.raw,
            progress = 0.0,
            result_int = null,
        )
      }
    }

    sessionExecutionJobQueueFront.offerJob(
        SessionExecutionJobOffer(
            sessionId = id,
        ),
    )
  }

  fun getAllSessions(): List<SessionDump> {
    return database.sessionQueries.selectAllSessions().executeAsList().map { it.toDump() }
  }
}
