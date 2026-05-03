package software.medusa.flow.core_service.session

@JvmInline
value class SessionId(
    val raw: Long,
)

@JvmInline
value class TaskId(
    val raw: String,
)

data class Session(
    val title: String,
    val taskGraph: TaskGraph,
)

data class SessionDump(
    val id: SessionId,
    val state: SessionState,
    val details: Session,
)

enum class SessionState {
  DRAFT,
  RUNNING,
}

data class RunningSessionProgress(
    val taskExecutionProgresses: List<TaskExecutionProgress>,
)

data class TaskExecutionProgress(
    val taskId: TaskId,
    val progress: Double,
)

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
      targetTask.sourceTaskIds.mapTo(linkedSetOf()) { sourceTaskId ->
        requireNotNull(taskById[sourceTaskId]) {
          "Source task $sourceTaskId for task ${targetTask.id} not found"
        }
      }
}

data class Task(
    val id: TaskId,
    val sourceTaskIds: List<TaskId>,
    val definition: Definition,
    val x: Double,
    val y: Double,
) {
  data class Definition(
      val label: String,
      val description: String,
  )
}
