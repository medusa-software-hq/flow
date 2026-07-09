package software.medusa.flow.worker

import java.nio.file.Files
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import software.medusa.commons.markdown.MdDocument
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind

private const val defaultHeartbeatIntervalMillis = 30_000L

/**
 * Clones the repo, runs the engine pipeline, and reports progress -- the real
 * [WrkSessionProcessor], replacing [WrkStubSessionProcessor].
 */
class WrkProperSessionProcessor(
    private val gitCloner: WrkGitCloner,
    private val taskCompleter: HrsTaskCompleter,
    private val publisher: WrkPublisher,
    private val heartbeatIntervalMillis: Long = defaultHeartbeatIntervalMillis,
    private val log: (String) -> Unit = ::println,
) : WrkSessionProcessor {
  override suspend fun process(
      session: Session,
      apiClient: WrkApiClient,
  ) {
    val cloneDirectory = Files.createTempDirectory("flow-worker-clone-")

    try {
      val taskDescription = parseTaskDescription(session.taskMarkdown)

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
              val prUrl =
                  publisher.publish(
                      repoFullName = session.repoFullName,
                      taskDescription = taskDescription,
                      workspace = workspace,
                  )

              apiClient.completeSession(sessionId = session.id, prUrl = prUrl)
            }

        is TaskCompletionResult.Failure ->
            apiClient.failSession(sessionId = session.id, failureSummary = result.toMarkdown())
      }
    } finally {
      cloneDirectory.toFile().deleteRecursively()
    }
  }

  private fun parseTaskDescription(
      taskMarkdown: String,
  ): HrsTaskDescription {
    val document = MdDocument.parse(markdownSource = taskMarkdown)
    return HrsTaskDescription(body = document.rootChapter.element)
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
}
