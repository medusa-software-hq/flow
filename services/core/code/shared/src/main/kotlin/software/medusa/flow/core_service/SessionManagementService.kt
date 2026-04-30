package software.medusa.flow.core_service

import java.util.UUID
import software.medusa.flow.db.FlowDatabase
import software.medusa.flow.db.Session
import software.medusa.grpc.flow.core_service.v1.TaskGraph

class SessionManagementService(private val database: FlowDatabase) {
  fun createSession(title: String, taskGraph: TaskGraph): Session {
    val sessionId = UUID.randomUUID().toString()
    val taskGraphProtoBytes = taskGraph.toByteArray()

    database.sessionQueries.insertSession(
        id = sessionId,
        title = title,
        task_graph_proto_bytes = taskGraphProtoBytes,
    )

    return database.sessionQueries.selectSessionById(id = sessionId).executeAsOne()
  }

  fun getSessionById(id: String): Session? =
      database.sessionQueries.selectSessionById(id).executeAsOneOrNull()

  fun updateSession(id: String, title: String, taskGraph: TaskGraph): Session? {
    database.sessionQueries.updateSessionTaskGraph(
        title = title,
        task_graph_proto_bytes = taskGraph.toByteArray(),
        id = id,
    )

    return getSessionById(id)
  }

  fun getAllSessions(): List<Session> = database.sessionQueries.selectAllSessions().executeAsList()

  fun decodeTaskGraph(session: Session): TaskGraph =
      TaskGraph.parseFrom(session.task_graph_proto_bytes)
}
