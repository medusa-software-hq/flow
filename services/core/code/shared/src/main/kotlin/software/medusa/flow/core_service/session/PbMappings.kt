package software.medusa.flow.core_service.session

import software.medusa.grpc.flow.control_service.v1.PbRunningSessionProgress
import software.medusa.grpc.flow.control_service.v1.PbSessionDetails
import software.medusa.grpc.flow.control_service.v1.PbSessionDraftState
import software.medusa.grpc.flow.control_service.v1.PbSessionDump
import software.medusa.grpc.flow.control_service.v1.PbSessionRunningState
import software.medusa.grpc.flow.control_service.v1.PbSessionState
import software.medusa.grpc.flow.control_service.v1.PbTask
import software.medusa.grpc.flow.control_service.v1.PbTaskExecutionProgress
import software.medusa.grpc.flow.control_service.v1.PbTaskGraph

fun Session.toPbSessionDetails(): PbSessionDetails =
    PbSessionDetails.newBuilder().setTitle(title).setTaskGraph(taskGraph.toPbTaskGraph()).build()

fun SessionDump.toPbSessionDump(): PbSessionDump =
    PbSessionDump.newBuilder()
        .setId(id.raw.toString())
        .setState(state.toPbSessionState())
        .setDetails(details.toPbSessionDetails())
        .build()

fun SessionState.toPbSessionState(): PbSessionState =
    PbSessionState.newBuilder()
        .apply {
          when (this@toPbSessionState) {
            SessionState.DRAFT -> draft = PbSessionDraftState.getDefaultInstance()
            SessionState.RUNNING -> running = PbSessionRunningState.getDefaultInstance()
          }
        }
        .build()

fun RunningSessionProgress.toPbRunningSessionProgress(): PbRunningSessionProgress =
    PbRunningSessionProgress.newBuilder()
        .addAllTaskExecutionProgresses(
            taskExecutionProgresses.map { it.toPbTaskExecutionProgress() }
        )
        .build()

fun TaskExecutionProgress.toPbTaskExecutionProgress(): PbTaskExecutionProgress =
    PbTaskExecutionProgress.newBuilder().setTaskId(taskId.raw).setProgress(progress).build()

fun TaskGraph.toPbTaskGraph(): PbTaskGraph =
    PbTaskGraph.newBuilder().addAllTasks(tasks.map { it.toPbTask() }).build()

fun Task.toPbTask(): PbTask =
    PbTask.newBuilder()
        .setId(id.raw)
        .setLabel(definition.label)
        .setDescription(definition.description)
        .addAllSourceTaskIds(sourceTaskIds.map(TaskId::raw))
        .setX(x)
        .setY(y)
        .build()

fun PbSessionDetails.toModel(): Session =
    Session(
        title = title,
        taskGraph = taskGraph.toModel(),
    )

fun PbSessionDump.toModel(): SessionDump =
    SessionDump(
        id = SessionId(raw = id.toLong()),
        state = state.toModel(),
        details = details.toModel(),
    )

fun PbSessionState.toModel(): SessionState =
    when (stateCase) {
      PbSessionState.StateCase.DRAFT -> SessionState.DRAFT
      PbSessionState.StateCase.RUNNING -> SessionState.RUNNING
      PbSessionState.StateCase.STATE_NOT_SET -> error("Session state is required")
    }

fun PbTaskGraph.toModel(): TaskGraph =
    TaskGraph(
        taskById = tasksList.map { it.toModel() }.associateBy(Task::id),
    )

fun PbTask.toModel(): Task =
    Task(
        id = TaskId(raw = id),
        definition =
            Task.Definition(
                label = label,
                description = description,
            ),
        sourceTaskIds = sourceTaskIdsList.map(::TaskId),
        x = x,
        y = y,
    )

fun TaskGraph.toProtoBytes(): ByteArray = toPbTaskGraph().toByteArray()

fun ByteArray.toTaskGraphModel(): TaskGraph = PbTaskGraph.parseFrom(this).toModel()
