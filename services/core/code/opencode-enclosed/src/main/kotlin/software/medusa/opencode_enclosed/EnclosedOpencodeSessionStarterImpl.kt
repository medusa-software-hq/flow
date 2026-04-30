package software.medusa.opencode_enclosed

import java.net.URI
import java.nio.file.Path
import software.medusa.commons.network.PortAllocator
import software.medusa.opencode_client.OpencodeClientImpl

private const val localhostHostname = "127.0.0.1"
private const val startupAttempts = 60
private const val startupPollIntervalMs = 500L

class EnclosedOpencodeSessionStarterImpl(
    private val portAllocator: PortAllocator,
    private val localOpencodeServerSpawner: LocalOpencodeServerSpawner,
    private val passwordGenerator: PasswordGenerator,
) : EnclosedOpencodeSessionStarter {
  /**
   * Starts a fresh OpenCode session in the given workspace.
   *
   * @return a handle to the spawned agent. The agent's session is initially clear.
   */
  override fun startSession(
      title: String,
      workingDirectoryPath: Path,
  ): EnclosedOpencodeSession {
    val port = portAllocator.allocate()
    val password = passwordGenerator.generatePassword()

    val process =
        localOpencodeServerSpawner.spawnLocalServer(
            workspacePath = workingDirectoryPath,
            hostname = localhostHostname,
            port = port,
            password = password,
        )

    @Suppress("HttpUrlsUsage") val serverUrl = URI("http://$localhostHostname:$port")

    val opencodeClient =
        OpencodeClientImpl.build(
            serverUri = serverUrl,
            serverPassword = password,
        )

    waitForServer(opencodeClient = opencodeClient, process = process)
    opencodeClient.enableLocalPermissions()

    val response = opencodeClient.createSession(title = title)

    return EnclosedOpencodeSessionImpl(
        title = requireNotNull(response.title),
        sessionId = response.id,
        serverUrl = serverUrl.toString(),
        opencodeClient = opencodeClient,
    )
  }

  private fun waitForServer(opencodeClient: OpencodeClientImpl, process: Process) {
    repeat(startupAttempts) { attempt ->
      if (!process.isAlive) {
        throw IllegalStateException(
            "Embedded OpenCode server exited before becoming ready (exit code ${process.exitValue()})"
        )
      }

      runCatching { opencodeClient.checkHealth() }
          .onSuccess {
            return
          }
          .onFailure { error ->
            if (attempt == startupAttempts - 1) {
              throw IllegalStateException(
                  "Embedded OpenCode server did not become ready: ${error.message}",
                  error,
              )
            }
          }

      Thread.sleep(startupPollIntervalMs)
    }
  }
}
