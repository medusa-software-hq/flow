package software.medusa.flow.core_service

import java.util.UUID
import software.medusa.flow.db.FlowDatabase
import software.medusa.flow.db.Session
import software.medusa.grpc.flow.core_service.v1.TaskGraph

class SessionManagementService(private val database: FlowDatabase) {
  fun createSession(taskGraph: TaskGraph): Session {
    val sessionId = UUID.randomUUID().toString()
    val taskGraphProtoBytes = taskGraph.toByteArray()

    database.sessionQueries.insertSession(
        id = sessionId,
        task_graph_proto_bytes = taskGraphProtoBytes,
    )

    return database.sessionQueries.selectSessionById(id = sessionId).executeAsOne()
  }

  fun getSessionById(id: String): Session? =
      database.sessionQueries.selectSessionById(id).executeAsOneOrNull()
}
