package software.medusa.flow.integration.nodejs.process

import java.nio.file.Path
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.flow.integration.nodejs.NjsCommand
import software.medusa.flow.integration.nodejs.NjsCommand.ExecutionResult
import software.medusa.flow.integration.nodejs.njsHermeticEnvironment

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
            environment = njsHermeticEnvironment(),
        )

    return ExecutionResult(
        exitCode = processOutcome.exitCode,
        standardOutput = processOutcome.standardOutput,
        errorOutput = processOutcome.errorOutput,
    )
  }
}
