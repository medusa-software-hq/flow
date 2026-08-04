package software.medusa.flow.integration.nodejs.process

import java.nio.file.Path
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.flow.integration.nodejs.NjsCommand
import software.medusa.flow.integration.nodejs.NjsCommand.ExecutionResult

class NjsProcessCommand(
    private val processSpawner: SysProcessSpawner,
    private val executable: SysExecutableHandle,
    private val workingDirectory: Path,
) : NjsCommand {
  override suspend fun execute(
      arguments: List<String>,
  ): ExecutionResult {
    val processOutcome =
        processSpawner.spawn(
            executable = executable,
            workingDirectory = workingDirectory,
            arguments = arguments,
            environment = cleanBuildEnvironment(),
        )

    return ExecutionResult(
        exitCode = processOutcome.exitCode,
        standardOutput = processOutcome.standardOutput,
        errorOutput = processOutcome.errorOutput,
    )
  }

  /**
   * Returns a copy of the current environment with worker-internal credential‑brokering variables
   * removed.
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
