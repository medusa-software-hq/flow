package software.medusa.flow.core_service.flows

import software.medusa.flow.db.Session as DbSession

fun DbSession.toDump(): FlowDump =
    FlowDump(
        id = FlowId(raw = id),
        state = state.toModel(),
        blueprint = toModel(),
    )

fun DbSession.toModel(): FlowBlueprint =
    FlowBlueprint(
        title = title,
        taskGraph = task_graph_proto_bytes.toTaskGraphModel(),
    )

fun String.toModel(): FlowState =
    when (this) {
      "draft" -> FlowState.DRAFT
      "running" -> FlowState.RUNNING
      else -> error("Unknown flow state: $this")
    }

fun FlowState.toDbValue(): String =
    when (this) {
      FlowState.DRAFT -> "draft"
      FlowState.RUNNING -> "running"
    }
