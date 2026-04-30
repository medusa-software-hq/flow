package software.medusa.flow.session

import software.medusa.flow.db.Session as DbSession

fun Session.toDbInsert(): DbInsertSession =
    DbInsertSession(
        id = id,
        title = title,
        taskGraphProtoBytes = taskGraph.toProtoBytes(),
    )

fun DbSession.toModel(): Session =
    Session(
        id = id,
        title = title,
        taskGraph = task_graph_proto_bytes.toTaskGraphModel(),
    )

data class DbInsertSession(
    val id: String,
    val title: String,
    val taskGraphProtoBytes: ByteArray,
)
