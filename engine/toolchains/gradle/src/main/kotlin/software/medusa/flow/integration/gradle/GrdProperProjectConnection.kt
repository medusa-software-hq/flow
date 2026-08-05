package software.medusa.flow.integration.gradle

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.BuildException
import org.gradle.tooling.ProjectConnection

/**
 * A Gradle project connection backed by the Gradle Tooling API.
 *
 * Created by [GrdProperProjectConnector]; owns the underlying [ProjectConnection] and releases it
 * on [close].
 */
class GrdProperProjectConnection
internal constructor(
    private val projectConnection: ProjectConnection,
) : GrdProjectConnection {
  /**
   * Runs the requested Gradle [taskName], capturing its standard and error output.
   *
   * The worker shares one `GRADLE_USER_HOME` across every session on the host (`UnpToolchainGate`),
   * so a build killed mid-download in an earlier, unrelated session can leave a truncated jar in
   * the module cache — every later build that reads it fails with "Could not read file: ...!/...",
   * regardless of what that build is doing. Self-heals exactly that case: evict the offending jar
   * and retry once with `--refresh-dependencies` so Gradle re-fetches it instead of trusting the
   * (now-missing) cache entry. Any other failure is reported as-is from the first attempt, since
   * retrying would just waste a build for a failure that won't go away.
   */
  override suspend fun runTask(taskName: GrdTaskName): GrdTaskResult =
      withContext(Dispatchers.IO) {
        val firstAttempt = runBuild(taskName, refreshDependencies = false)
        if (firstAttempt.status != GrdTaskResult.Status.Failure) return@withContext firstAttempt

        val poisonedCacheJar =
            findPoisonedCacheJar(firstAttempt.standardOutput + firstAttempt.errorOutput)
                ?: return@withContext firstAttempt

        poisonedCacheJar.delete()
        runBuild(taskName, refreshDependencies = true)
      }

  private fun runBuild(
      taskName: GrdTaskName,
      refreshDependencies: Boolean,
  ): GrdTaskResult {
    val standardOutput = ByteArrayOutputStream()
    val errorOutput = ByteArrayOutputStream()

    val buildOutcome = runCatching {
      projectConnection
          .newBuild()
          .forTasks(taskName.name)
          .apply { if (refreshDependencies) withArguments("--refresh-dependencies") }
          .setStandardOutput(standardOutput)
          .setStandardError(errorOutput)
          .setEnvironmentVariables(grdHermeticEnvironment())
          .run()
    }

    // A failing build is a normal outcome reported as a Failure status; anything other than a
    // build failure (e.g. an unusable connection) is a genuine error and propagates.
    val failure = buildOutcome.exceptionOrNull()
    if (failure != null && failure !is BuildException) {
      throw failure
    }

    return GrdTaskResult(
        status =
            if (buildOutcome.isSuccess) GrdTaskResult.Status.Success
            else GrdTaskResult.Status.Failure,
        standardOutput = standardOutput.toString(Charsets.UTF_8),
        errorOutput = errorOutput.toString(Charsets.UTF_8),
    )
  }

  override fun close() {
    projectConnection.close()
  }
}
