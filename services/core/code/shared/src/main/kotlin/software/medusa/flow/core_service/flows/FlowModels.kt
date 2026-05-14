package software.medusa.flow.core_service.flows

@JvmInline
value class FlowId(
    val raw: Long,
)

@JvmInline
value class TaskId(
    val raw: String,
)

data class FlowBlueprint(
    val title: String,
    val taskGraph: TaskGraph,
) {
  data class TaskGraph(
      val taskById: Map<TaskId, Task>,
  ) {
    val tasks: List<Task>
      get() = taskById.values.toList()

    init {
      require(taskById.keys == taskById.values.mapTo(linkedSetOf()) { it.id }) {
        "Task graph keys must match task IDs"
      }
    }

    fun getSourceTasks(targetTask: Task): Set<Task> =
        targetTask.inputTaskIds.mapTo(linkedSetOf()) { sourceTaskId ->
          requireNotNull(taskById[sourceTaskId]) {
            "Source task $sourceTaskId for task ${targetTask.id} not found"
          }
        }
  }
}

data class FlowDump(
    val id: FlowId,
    val state: FlowState,
    val blueprint: FlowBlueprint,
)

enum class FlowState {
  DRAFT,
  RUNNING,
}

data class RunningFlowProgress(
    val taskExecutionProgresses: List<TaskExecutionProgress>,
)

data class TaskExecutionProgress(
    val taskId: TaskId,
    val progress: Double,
)

data class Task(
    val id: TaskId,
    val inputTaskIds: List<TaskId>,
    val definition: Definition,
    val x: Double,
    val y: Double,
) {
  sealed interface Definition

  data class FeatureDefinition(
      val label: String,
      val description: String,
  ) : Definition

  data object BlankDefinition : Definition

  data object MergeDefinition : Definition
}
