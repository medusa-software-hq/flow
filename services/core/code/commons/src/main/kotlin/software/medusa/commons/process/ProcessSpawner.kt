package software.medusa.commons.process

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Utility class to spawn and manage child processes. It keeps track of all spawned processes and
 * ensures they are terminated when the parent process exits.
 */
class ProcessSpawner private constructor() {
  companion object {
    fun create(runtime: Runtime): ProcessSpawner {
      val processSpawner = ProcessSpawner()

      runtime.addShutdownHook(Thread { processSpawner.destroyAll() })

      return processSpawner
    }
  }

  private val childProcesses = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()

  fun spawn(
      executableHandle: ExecutableHandle,
      workingDirectoryPath: Path,
      args: List<String>,
      env: Map<String, String>,
  ): Process {
    val spawnedProcess =
        ProcessBuilder(listOf(executableHandle.path.toString()) + args)
            .inheritIO()
            .directory(workingDirectoryPath.toFile())
            .redirectErrorStream(true)
            .apply { environment().apply { env.forEach { (key, value) -> this[key] = value } } }
            .start()

    childProcesses.add(spawnedProcess)

    spawnedProcess.onExit().thenRun { childProcesses.remove(spawnedProcess) }

    return spawnedProcess
  }

  private fun destroyAll(timeout: Duration = 3.seconds, graceMillis: Long = 3_000) {
    for (childProcess in childProcesses) {
      runCatching { childProcess.destroy() }
    }

    val deadline = System.currentTimeMillis() + graceMillis

    for (childProcess in childProcesses) {
      val remaining = deadline - System.currentTimeMillis()

      if (remaining > 0) {
        runCatching { childProcess.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
      }
    }

    for (childProcess in childProcesses) {
      if (childProcess.isAlive) {
        runCatching { childProcess.destroyForcibly() }
      }
    }
  }
}
