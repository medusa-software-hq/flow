package software.medusa.flow.core_service.session

import software.medusa.grpc.flow.control_service.v1.PbSessionSummary
import software.medusa.grpc.flow.control_service.v1.PbTask
import software.medusa.grpc.flow.control_service.v1.PbTaskGraph

fun Session.toPbSessionSummary(): PbSessionSummary =
    PbSessionSummary.newBuilder()
        .setSessionId(id)
        .setTitle(title)
        .setTaskGraph(taskGraph.toPbTaskGraph())
        .build()

fun TaskGraph.toPbTaskGraph(): PbTaskGraph =
    PbTaskGraph.newBuilder().addAllTasks(tasks.map { it.toPbTask() }).build()

fun Task.toPbTask(): PbTask =
    PbTask.newBuilder()
        .setId(id)
        .setLabel(label)
        .setDescription(description)
        .addAllSourceTaskIds(sourceTaskIds)
        .setX(x)
        .setY(y)
        .build()

fun PbTaskGraph.toModel(): TaskGraph =
    TaskGraph(
        tasks = tasksList.map { it.toModel() },
    )

fun PbTask.toModel(): Task =
    Task(
        id = id,
        label = label,
        description = description,
        sourceTaskIds = sourceTaskIdsList,
        x = x,
        y = y,
    )

fun TaskGraph.toProtoBytes(): ByteArray = toPbTaskGraph().toByteArray()

fun ByteArray.toTaskGraphModel(): TaskGraph = PbTaskGraph.parseFrom(this).toModel()
