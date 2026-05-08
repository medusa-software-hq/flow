package software.medusa.flow.core_service.flows

import software.medusa.flow.core_service.flows.FlowBlueprint.TaskGraph
import software.medusa.grpc.flow.control_service.v1.PbRunningSessionProgress
import software.medusa.grpc.flow.control_service.v1.PbSessionDetails
import software.medusa.grpc.flow.control_service.v1.PbSessionDraftState
import software.medusa.grpc.flow.control_service.v1.PbSessionDump
import software.medusa.grpc.flow.control_service.v1.PbSessionRunningState
import software.medusa.grpc.flow.control_service.v1.PbSessionState
import software.medusa.grpc.flow.control_service.v1.PbTask
import software.medusa.grpc.flow.control_service.v1.PbTaskExecutionProgress
import software.medusa.grpc.flow.control_service.v1.PbTaskGraph

fun FlowBlueprint.toPbSessionDetails(): PbSessionDetails =
    PbSessionDetails.newBuilder().setTitle(title).setTaskGraph(taskGraph.toPbTaskGraph()).build()

fun FlowDump.toPbSessionDump(): PbSessionDump =
    PbSessionDump.newBuilder()
        .setId(id.raw.toString())
        .setState(state.toPbSessionState())
        .setDetails(blueprint.toPbSessionDetails())
        .build()

fun FlowState.toPbSessionState(): PbSessionState =
    PbSessionState.newBuilder()
        .apply {
          when (this@toPbSessionState) {
            FlowState.DRAFT -> draft = PbSessionDraftState.getDefaultInstance()
            FlowState.RUNNING -> running = PbSessionRunningState.getDefaultInstance()
          }
        }
        .build()

fun RunningFlowProgress.toPbRunningSessionProgress(): PbRunningSessionProgress =
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
        .apply {
          when (val currentDefinition = definition) {
            is Task.FeatureDefinition -> {
              label = currentDefinition.label
              description = currentDefinition.description
            }

            Task.MergeDefinition -> {
              label = ""
              description = ""
            }
          }
        }
        .addAllSourceTaskIds(inputTaskIds.map(TaskId::raw))
        .setX(x)
        .setY(y)
        .build()

fun PbSessionDetails.toModel(): FlowBlueprint =
    FlowBlueprint(
        title = title,
        taskGraph = taskGraph.toModel(),
    )

fun PbSessionDump.toModel(): FlowDump =
    FlowDump(
        id = FlowId(raw = id.toLong()),
        state = state.toModel(),
        blueprint = details.toModel(),
    )

fun PbSessionState.toModel(): FlowState =
    when (stateCase) {
      PbSessionState.StateCase.DRAFT -> FlowState.DRAFT
      PbSessionState.StateCase.RUNNING -> FlowState.RUNNING
      PbSessionState.StateCase.STATE_NOT_SET -> error("FlowBlueprint state is required")
    }

fun PbTaskGraph.toModel(): TaskGraph =
    TaskGraph(
        taskById = tasksList.map { it.toModel() }.associateBy(Task::id),
    )

fun PbTask.toModel(): Task =
    Task(
        id = TaskId(raw = id),
        definition =
            if (sourceTaskIdsCount > 1) {
              Task.MergeDefinition
            } else {
              Task.FeatureDefinition(
                  label = label,
                  description = description,
              )
            },
        inputTaskIds = sourceTaskIdsList.map(::TaskId),
        x = x,
        y = y,
    )

fun TaskGraph.toProtoBytes(): ByteArray = toPbTaskGraph().toByteArray()

fun ByteArray.toTaskGraphModel(): TaskGraph = PbTaskGraph.parseFrom(this).toModel()
