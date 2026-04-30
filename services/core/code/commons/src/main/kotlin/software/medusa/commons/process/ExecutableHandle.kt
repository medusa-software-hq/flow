package software.medusa.commons.process

import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

private const val pathEnvVarName = "PATH"

/**
 * A handle to an executable file that can be spawned as a child process, represented by a path to
 * the executable. The path is proven to be an absolute path to a regular file with executable
 * permissions, but it's not guaranteed to remain valid or executable by the time it's used to spawn
 * a process.
 */
@JvmInline
value class ExecutableHandle(val path: Path) {
  companion object {
    /**
     * Resolves an absolute path to an executable file into an [ExecutableHandle]. Symlinks are
     * followed.
     *
     * @throws IllegalArgumentException if the path is not absolute, not a regular file, or not
     *   executable.
     */
    fun resolve(execFilePath: Path): ExecutableHandle {
      require(execFilePath.isAbsolute) {
        "Expected an absolute path to an executable, but got: $execFilePath"
      }
      require(execFilePath.isRegularFile()) {
        "Expected a regular file for an executable, but got: $execFilePath"
      }
      require(execFilePath.isExecutable()) { "Expected an executable file, but got: $execFilePath" }
      return ExecutableHandle(execFilePath)
    }

    /**
     * Locate the given command in the system against the PATH environment variable and return an
     * [ExecutableHandle] to it. Symlinks are followed.
     *
     * @throws IllegalArgumentException if the command cannot be found in the system.
     */
    fun locate(commandName: String): ExecutableHandle {
      val pathEnvVarContent =
          System.getenv(pathEnvVarName) ?: error("PATH environment variable is not set")
      val pathEntries = pathEnvVarContent.split(File.pathSeparatorChar)

      return pathEntries.firstNotNullOfOrNull { binDirPathStr: String ->
        val binDirPath = Path.of(binDirPathStr)
        val execFilePath = binDirPath.resolve(commandName)

        if (!execFilePath.isAbsolute) {
          throw IllegalStateException("Resolved executable path is not absolute: $execFilePath")
        }

        when {
          execFilePath.exists() -> resolve(execFilePath = execFilePath)
          else -> null
        }
      }
          ?: throw IllegalArgumentException(
              "Command '$commandName' cannot be found in the system PATH"
          )
    }
  }
}
