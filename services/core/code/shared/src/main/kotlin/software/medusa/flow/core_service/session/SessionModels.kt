package software.medusa.flow.core_service.session

data class Session(
    val id: String,
    val title: String,
    val taskGraph: TaskGraph,
)

data class TaskGraph(
    val tasks: List<Task>,
)

data class Task(
    val id: String,
    val label: String,
    val description: String,
    val sourceTaskIds: List<String>,
    val x: Double,
    val y: Double,
)
