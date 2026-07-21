package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree

/**
 * A [HrsTaskCompleter] that refuses to run, for wiring an engine slot that a given *test* harness
 * deliberately cannot provide — e.g. the scripted sad-path worker, which runs on a runner without
 * the `claude` binary and only ever drives `UNSPECIFIED`/builtin sessions.
 *
 * Workers are uniform in production (every worker has every engine), so this never appears there:
 * it exists only so a test harness that can't meet a real engine's requirements can stand a slot up
 * without the *production* wiring paying for it (locate-at-startup of a binary the test lacks). If
 * a session is ever routed here, that is a wiring error and it fails loudly rather than silently.
 */
class UnimplementedHrsTaskCompleter(
    private val engineName: String,
) : HrsTaskCompleter {
  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: HrsTaskCompleter.Observer,
  ): HrsTaskCompleter.TaskCompletionResult =
      error(
          "The $engineName engine is not available in this worker. This completer is a test-only " +
              "placeholder; a session should never have been routed to it.",
      )
}
