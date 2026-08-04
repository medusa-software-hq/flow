package software.medusa.flow.harness

import software.medusa.flow.physical_workspace.PhwWorkspace

/**
 * Runs [block] with this allocated [PhwWorkspace], closing the workspace unless [block] returns a
 * [HrsTaskCompleter.TaskCompletionResult.Success] — the only outcome that hands the workspace off
 * to the caller (wrapped as its [HrsTaskCompleter.TaskCompletionResult.Success.temporaryWorkspace],
 * closed later by whoever publishes it). Every other exit — a structured
 * [HrsTaskCompleter.TaskCompletionResult.Failure], a thrown exception (engine crash, auth failure),
 * or cancellation (a session abort) — must close the workspace here, or its temp directory is
 * orphaned on disk. `close()` itself is non-suspending, so it still runs from `finally` under
 * cancellation without needing a `NonCancellable` wrapper.
 */
internal suspend inline fun PhwWorkspace.closeUnlessSuccessful(
    block: () -> HrsTaskCompleter.TaskCompletionResult,
): HrsTaskCompleter.TaskCompletionResult {
  var succeeded = false
  try {
    val result = block()
    succeeded = result is HrsTaskCompleter.TaskCompletionResult.Success
    return result
  } finally {
    if (!succeeded) close()
  }
}
