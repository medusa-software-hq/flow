package software.medusa.opencode_enclosed

import java.nio.file.Path

/**
 * Starts an enclosed OpenCode session. An "enclosed OpenCode session" encapsulates a single
 * instance of an OpenCode server bound to a specific directory, a single session within that server
 * and a client bound to that server / session.
 */
interface EnclosedOpencodeSessionStarter {
  fun startSession(
      title: String,
      workingDirectoryPath: Path,
  ): EnclosedOpencodeSession
}
