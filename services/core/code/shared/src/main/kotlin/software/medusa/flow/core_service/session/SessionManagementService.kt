package software.medusa.flow.core_service.session

import java.util.UUID
import software.medusa.flow.db.FlowDatabase
import software.medusa.flow.db.Session as DbSession

class SessionManagementService(private val database: FlowDatabase) {
  fun createSession(title: String, taskGraph: TaskGraph): Session {
    val session =
        Session(
            id = UUID.randomUUID().toString(),
            title = title,
            taskGraph = taskGraph,
        )
    val dbInsertSession = session.toDbInsert()

    database.sessionQueries.insertSession(
        id = dbInsertSession.id,
        title = dbInsertSession.title,
        task_graph_proto_bytes = dbInsertSession.taskGraphProtoBytes,
    )

    return requireNotNull(getSessionById(session.id))
  }

  fun getSessionById(id: String): Session? =
      database.sessionQueries.selectSessionById(id).executeAsOneOrNull()?.toModel()

  fun updateSession(id: String, title: String, taskGraph: TaskGraph): Session? {
    val dbInsertSession =
        Session(
                id = id,
                title = title,
                taskGraph = taskGraph,
            )
            .toDbInsert()

    database.sessionQueries.updateSessionTaskGraph(
        title = dbInsertSession.title,
        task_graph_proto_bytes = dbInsertSession.taskGraphProtoBytes,
        id = dbInsertSession.id,
    )

    return getSessionById(id)
  }

  fun getAllSessions(): List<Session> =
      database.sessionQueries.selectAllSessions().executeAsList().map(DbSession::toModel)
}
