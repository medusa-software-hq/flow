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

/** Clones the repo, runs the engine pipeline, publishes, and reports progress. */
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
                    )
                  } catch (e: Exception) {
                    apiClient.failSession(
                        sessionId = session.id,
                        failureSummary = "Failed to publish the result:\n\n```\n${e.message}\n```",
                    )
                    return@use
                  }

              when (publishResult) {
                is WrkPublishResult.Published ->
                    apiClient.completeSession(sessionId = session.id, prUrl = publishResult.prUrl)

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
        description = HrsTaskDescription(body = document.rootChapter.element),
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
}
