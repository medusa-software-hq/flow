package software.medusa.flow.worker

import java.nio.file.Files
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import software.medusa.commons.markdown.MdDocument
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind

/** Clones the repo, runs the engine pipeline, publishes, and reports progress. */
class WrkProperSessionProcessor(
    private val gitCloner: WrkGitCloner,
    private val engineResolver: WrkEngineResolver,
    private val publisher: WrkPublisher,
    private val heartbeatIntervalMillis: Long = Companion.defaultHeartbeatIntervalMillis,
    private val log: (String) -> Unit = ::println,
    // Runs after a successful publish (branch pushed, PR opened) but before the session is marked
    // complete. Production leaves it a no-op; the scripted "crash mid-publish" sad-path test
    // injects
    // a hard halt here, so the control plane never hears the work landed — see the sad-path suite.
    private val beforeCompleteSession: suspend () -> Unit = {},
) : WrkSessionProcessor {
  override suspend fun process(
      session: Session,
      apiClient: WrkApiClient,
  ) {
    val cloneDirectory = Files.createTempDirectory("flow-worker-clone-")

    try {
      val (taskDescription, taskHeading) = parseTask(session.taskMarkdown)

      apiClient.appendSessionEvent(
          sessionId = session.id,
          kind = SessionEventKind.SESSION_EVENT_KIND_WORKSPACE_PREPARING,
          message = "Cloning `${session.repoFullName}`",
      )

      val gitWorktree =
          try {
            gitCloner.cloneDefaultBranch(
                repoFullName = session.repoFullName,
                targetDirectory = cloneDirectory,
            )
          } catch (e: Exception) {
            apiClient.failSession(
                sessionId = session.id,
                failureSummary =
                    "Failed to clone `${session.repoFullName}`:\n\n```\n${e.message}\n```",
            )
            return
          }

      val observer =
          WrkReportingTaskObserver(sessionId = session.id, apiClient = apiClient, log = log)

      // A6: pick the completer for the session's requested engine; UNSPECIFIED → the worker
      // default.
      val taskCompleter = engineResolver.resolve(session.engine)

      val result = coroutineScope {
        val heartbeatJob = launch { heartbeatLoop(session.id, apiClient) }
        try {
          taskCompleter.completeTask(
              sourceGitWorktree = gitWorktree,
              taskDescription = taskDescription,
              observer = observer,
          )
        } finally {
          heartbeatJob.cancel()
        }
      }

      when (result) {
        is TaskCompletionResult.Success ->
            result.temporaryWorkspace.use { workspace ->
              apiClient.appendSessionEvent(
                  sessionId = session.id,
                  kind = SessionEventKind.SESSION_EVENT_KIND_PUBLISHING,
                  message = "Publishing the result as a pull request",
              )

              val publishResult =
                  try {
                    publisher.publish(
                        repoFullName = session.repoFullName,
                        sessionId = session.id,
                        taskHeading = taskHeading,
                        taskMarkdown = session.taskMarkdown,
                        cloneDirectory = cloneDirectory,
                        workspace = workspace,
                        issueNumber = session.issueNumber.takeIf { it > 0 },
                    )
                  } catch (e: Exception) {
                    apiClient.failSession(
                        sessionId = session.id,
                        failureSummary = "Failed to publish the result:\n\n```\n${e.message}\n```",
                    )
                    return@use
                  }

              when (publishResult) {
                is WrkPublishResult.Published -> {
                  beforeCompleteSession()
                  apiClient.completeSession(sessionId = session.id, prUrl = publishResult.prUrl)
                }

                WrkPublishResult.NoChanges ->
                    apiClient.failSession(
                        sessionId = session.id,
                        failureSummary = "Engine produced no changes",
                    )
              }
            }

        is TaskCompletionResult.Failure ->
            apiClient.failSession(sessionId = session.id, failureSummary = result.toMarkdown())
      }
    } finally {
      cloneDirectory.toFile().deleteRecursively()
    }
  }

  private data class ParsedTask(
      val description: HrsTaskDescription,
      val heading: String,
  )

  private fun parseTask(
      taskMarkdown: String,
  ): ParsedTask {
    val document = MdDocument.parse(markdownSource = taskMarkdown)
    val heading = document.rootChapter.title.inlineNodes.joinToString("") { it.render() }

    return ParsedTask(
        // The whole root chapter, not just `.element` (the blocks directly under the `#` title,
        // before the first `##`). A real issue keeps its substance in `##` sub-sections, so the
        // lead element is typically empty — passing it alone leaves the engine with no task.
        description = HrsTaskDescription(body = document.rootChapter),
        heading = heading,
    )
  }

  private suspend fun heartbeatLoop(
      sessionId: String,
      apiClient: WrkApiClient,
  ) {
    while (true) {
      delay(heartbeatIntervalMillis)
      try {
        apiClient.heartbeat(sessionId)
      } catch (e: Exception) {
        log("Session $sessionId: heartbeat failed ($e)")
      }
    }
  }

  companion object {
    const val defaultHeartbeatIntervalMillis = 30_000L
  }
}
