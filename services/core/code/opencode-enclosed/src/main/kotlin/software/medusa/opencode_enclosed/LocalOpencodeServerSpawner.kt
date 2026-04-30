package software.medusa.opencode_enclosed

import java.nio.file.Path
import software.medusa.commons.process.ExecutableHandle
import software.medusa.commons.process.ProcessSpawner

private const val opencodeServerPasswordEnvVarName = "OPENCODE_SERVER_PASSWORD"

class LocalOpencodeServerSpawner(
    private val processSpawner: ProcessSpawner,
    private val opencodeExecutableHandle: ExecutableHandle,
) {
  /**
   * Spawns a local OpenCode server. The server is not guaranteed to be ready to accept requests by
   * the time this function returns.
   */
  fun spawnLocalServer(
      workspacePath: Path,
      hostname: String,
      port: Int,
      password: String,
  ): Process =
      processSpawner.spawn(
          executableHandle = opencodeExecutableHandle,
          workingDirectoryPath = workspacePath,
          args = listOf("serve", "--hostname", hostname, "--port", port.toString()),
          env = mapOf(opencodeServerPasswordEnvVarName to password),
      )
}
