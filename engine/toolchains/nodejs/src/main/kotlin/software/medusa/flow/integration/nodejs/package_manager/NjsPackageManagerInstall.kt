package software.medusa.flow.integration.nodejs.package_manager

import java.nio.file.Path
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

/**
 * Runs a package-manager install command and fails loudly when it exits non-zero.
 *
 * [SysProcessSpawner.spawn] reports the child's exit code in its outcome but does *not* throw on
 * failure — the caller is expected to inspect it. The install callers used to discard that outcome
 * entirely, so a failed install (a full disk, a lockfile mismatch, no registry access) was
 * indistinguishable from a successful one: control flowed on to command resolution, which then
 * failed with a misleading "command could not be resolved", while the real cause — sitting in the
 * tool's own output — had already been thrown away. That swallowed error cost a full in-container
 * repro to diagnose a plain `ENOSPC`.
 *
 * Surfacing the exit code together with the captured output turns that into a one-line diagnosis,
 * and stops a later command-resolution failure from misattributing the blame.
 */
internal suspend fun SysProcessSpawner.runInstallOrThrow(
    label: String,
    executable: SysExecutableHandle,
    workingDirectory: Path,
    arguments: List<String>,
) {
  val outcome =
      spawn(
          executable = executable,
          workingDirectory = workingDirectory,
          arguments = arguments,
      )

  check(outcome.exitCode == 0) {
    val commandLine = (listOf(label) + arguments).joinToString(separator = " ")
    val output =
        listOf(outcome.standardOutput, outcome.errorOutput)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
            .trim()

    buildString {
      append("`$commandLine` failed (exit ${outcome.exitCode}) in $workingDirectory")
      if (output.isEmpty()) {
        append(" (no output captured).")
      } else {
        append(":\n")
        append(output)
      }
    }
  }
}
