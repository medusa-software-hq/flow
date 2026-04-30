package software.medusa.flow.session

import java.util.UUID
import software.medusa.flow.db.FlowDatabase
import software.medusa.flow.db.Session

class SessionManagementService(private val database: FlowDatabase) {
  fun createSession(title: String, taskGraphProtoBytes: ByteArray): Session {
    val sessionId = UUID.randomUUID().toString()

    database.sessionQueries.insertSession(
        id = sessionId,
        title = title,
        task_graph_proto_bytes = taskGraphProtoBytes,
    )

    return database.sessionQueries.selectSessionById(id = sessionId).executeAsOne()
  }

  fun getSessionById(id: String): Session? =
      database.sessionQueries.selectSessionById(id).executeAsOneOrNull()

  fun updateSession(id: String, title: String, taskGraphProtoBytes: ByteArray): Session? {
    database.sessionQueries.updateSessionTaskGraph(
        title = title,
        task_graph_proto_bytes = taskGraphProtoBytes,
        id = id,
    )

    return getSessionById(id)
  }

  fun getAllSessions(): List<Session> = database.sessionQueries.selectAllSessions().executeAsList()
}
