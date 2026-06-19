package software.medusa.flow.integration.gradle

/** Describes the textual outputs and status of a Gradle task invocation. */
data class GrdTaskResult(
    val status: Status,
    val standardOutput: String,
    val errorOutput: String,
) {
  /** Represents either a successful or failed Gradle task completion. */
  sealed interface Status {
    /** Task completed without Gradle reporting errors. */
    data object Success : Status

    /** Task failed because Gradle reported errors. */
    data object Failure : Status
  }
}
