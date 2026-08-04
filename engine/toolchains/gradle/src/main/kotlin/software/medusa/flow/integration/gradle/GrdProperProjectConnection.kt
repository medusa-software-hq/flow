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
  /** Runs the requested Gradle [taskName], capturing its standard and error output. */
  override suspend fun runTask(taskName: GrdTaskName): GrdTaskResult =
      withContext(Dispatchers.IO) {
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()

        val buildOutcome = runCatching {
          projectConnection
              .newBuild()
              .forTasks(taskName.name)
              .setStandardOutput(standardOutput)
              .setStandardError(errorOutput)
              .setEnvironmentVariables(cleanBuildEnvironment())
              .run()
        }

        // A failing build is a normal outcome reported as a Failure status; anything other than a
        // build failure (e.g. an unusable connection) is a genuine error and propagates.
        val failure = buildOutcome.exceptionOrNull()
        if (failure != null && failure !is BuildException) {
          throw failure
        }

        GrdTaskResult(
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

  /**
   * Returns a copy of the current environment with worker-internal credential‑brokering variables
   * removed, so that the repo build/test subprocess sees a clean, CI‑like environment.
   */
  private fun cleanBuildEnvironment(): Map<String, String> {
    val env = System.getenv().toMutableMap()
    env.keys.removeAll(CREDENTIAL_BROKER_VARS)
    return env
  }

  companion object {
    /** Environment variable keys that the worker uses for Beacon credential brokering. */
    private val CREDENTIAL_BROKER_VARS: Set<String> =
        setOf(
            "GCE_METADATA_HOST",
            "GCE_METADATA_IP",
            "GCE_METADATA_ROOT",
            "GCE_METADATA_PORT",
        )
  }
}
