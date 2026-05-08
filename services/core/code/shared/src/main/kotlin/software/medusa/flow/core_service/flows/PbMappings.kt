package software.medusa.flow.core_service.flows

import software.medusa.flow.core_service.flows.FlowBlueprint.TaskGraph
import software.medusa.grpc.flow.control_service.v1.PbBlankTaskDefinition
import software.medusa.grpc.flow.control_service.v1.PbFeatureTaskDefinition
import software.medusa.grpc.flow.control_service.v1.PbFlowDetails
import software.medusa.grpc.flow.control_service.v1.PbFlowDraftState
import software.medusa.grpc.flow.control_service.v1.PbFlowDump
import software.medusa.grpc.flow.control_service.v1.PbFlowRunningState
import software.medusa.grpc.flow.control_service.v1.PbFlowState
import software.medusa.grpc.flow.control_service.v1.PbMergeTaskDefinition
import software.medusa.grpc.flow.control_service.v1.PbRunningFlowProgress
import software.medusa.grpc.flow.control_service.v1.PbTask
import software.medusa.grpc.flow.control_service.v1.PbTaskExecutionProgress
import software.medusa.grpc.flow.control_service.v1.PbTaskGraph

fun FlowBlueprint.toPbFlowDetails(): PbFlowDetails =
    PbFlowDetails.newBuilder().setTitle(title).setTaskGraph(taskGraph.toPbTaskGraph()).build()

fun FlowDump.toPbFlowDump(): PbFlowDump =
    PbFlowDump.newBuilder()
        .setId(id.raw.toString())
        .setState(state.toPbFlowState())
        .setDetails(blueprint.toPbFlowDetails())
        .build()

fun FlowState.toPbFlowState(): PbFlowState =
    PbFlowState.newBuilder()
        .apply {
          when (this@toPbFlowState) {
            FlowState.DRAFT -> draft = PbFlowDraftState.getDefaultInstance()
            FlowState.RUNNING -> running = PbFlowRunningState.getDefaultInstance()
          }
        }
        .build()

fun RunningFlowProgress.toPbRunningFlowProgress(): PbRunningFlowProgress =
    PbRunningFlowProgress.newBuilder()
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
            Task.BlankDefinition -> {
              blankTask = PbBlankTaskDefinition.getDefaultInstance()
            }

            is Task.FeatureDefinition -> {
              featureTask =
                  PbFeatureTaskDefinition.newBuilder()
                      .setLabel(currentDefinition.label)
                      .setDescription(currentDefinition.description)
                      .build()
            }

            Task.MergeDefinition -> {
              mergeTask = PbMergeTaskDefinition.getDefaultInstance()
            }
          }
        }
        .addAllSourceTaskIds(inputTaskIds.map(TaskId::raw))
        .setX(x)
        .setY(y)
        .build()

fun PbFlowDetails.toModel(): FlowBlueprint =
    FlowBlueprint(
        title = title,
        taskGraph = taskGraph.toModel(),
    )

fun PbFlowDump.toModel(): FlowDump =
    FlowDump(
        id = FlowId(raw = id.toLong()),
        state = state.toModel(),
        blueprint = details.toModel(),
    )

fun PbFlowState.toModel(): FlowState =
    when (stateCase) {
      PbFlowState.StateCase.DRAFT -> FlowState.DRAFT
      PbFlowState.StateCase.RUNNING -> FlowState.RUNNING
      PbFlowState.StateCase.STATE_NOT_SET -> error("FlowBlueprint state is required")
    }

fun PbTaskGraph.toModel(): TaskGraph =
    TaskGraph(
        taskById = tasksList.map { it.toModel() }.associateBy(Task::id),
    )

fun PbTask.toModel(): Task =
    Task(
        id = TaskId(raw = id),
        definition =
            when (definitionCase) {
              PbTask.DefinitionCase.BLANK_TASK -> Task.BlankDefinition

              PbTask.DefinitionCase.FEATURE_TASK ->
                  Task.FeatureDefinition(
                      label = featureTask.label,
                      description = featureTask.description,
                  )

              PbTask.DefinitionCase.MERGE_TASK -> Task.MergeDefinition
              PbTask.DefinitionCase.DEFINITION_NOT_SET -> error("Task kind is required")
            },
        inputTaskIds = sourceTaskIdsList.map(::TaskId),
        x = x,
        y = y,
    )

fun TaskGraph.toProtoBytes(): ByteArray = toPbTaskGraph().toByteArray()

fun ByteArray.toTaskGraphModel(): TaskGraph = PbTaskGraph.parseFrom(this).toModel()
