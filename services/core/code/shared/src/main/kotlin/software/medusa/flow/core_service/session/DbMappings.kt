package software.medusa.flow.core_service.session

import software.medusa.flow.db.Session as DbSession

fun DbSession.toDump(): SessionDump =
    SessionDump(
        id = SessionId(raw = id),
        state = state.toModel(),
        details =
            Session(
                title = title,
                taskGraph = task_graph_proto_bytes.toTaskGraphModel(),
            ),
    )

fun String.toModel(): SessionState =
    when (this) {
      "draft" -> SessionState.DRAFT
      "running" -> SessionState.RUNNING
      else -> error("Unknown session state: $this")
    }

fun SessionState.toDbValue(): String =
    when (this) {
      SessionState.DRAFT -> "draft"
      SessionState.RUNNING -> "running"
    }
