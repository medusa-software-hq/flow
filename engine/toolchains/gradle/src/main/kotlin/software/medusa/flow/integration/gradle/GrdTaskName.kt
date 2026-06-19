package software.medusa.flow.integration.gradle

/** Represents a canonical Gradle task name that can be passed to [GrdProjectConnection.runTask]. */
@JvmInline
value class GrdTaskName(
    val name: String,
)
