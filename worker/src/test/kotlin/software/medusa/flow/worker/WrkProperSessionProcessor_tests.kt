package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter.JointOperationPhase
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.v1.session

private fun tempGitWorktree(): GitWorktree = runBlocking {
  val directory = java.nio.file.Files.createTempDirectory("wrk-fake-worktree-")
  ProcessBuilder("git", "init", "-q").directory(directory.toFile()).start().waitFor()
  GitWorktree.load(
      repoDirectory = UfsNioDirectory(directoryPath = directory),
      globalFilter = GitWorktreeFilter.Passive,
  )
}

private class FakeReadonlyTemporaryWorkspace(
    override val rootDirectory: UfsReadonlyDirectory,
) : HrsReadonlyTemporaryWorkspace {
  var closed = false

  override fun close() {
    closed = true
  }
}

private class FakeTaskCompleter(
    private val result: TaskCompletionResult,
) : HrsTaskCompleter {
  var observedPhases = 0

  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult {
    observer.observePhase(software.medusa.flow.harness.HrsPipelinePhase.WorkspacePreparing)
    observedPhases++
    return result
  }
}

class WrkProperSessionProcessor_tests {
  private fun testSession(): software.medusa.flow.v1.Session = session {
    id = "s1"
    repoFullName = "acme/app"
    taskMarkdown = "# Do the thing"
  }

  @Test
  fun `a successful run publishes and completes the session`() = runBlocking {
    val worktree = tempGitWorktree()
    val fakeWorkspace =
        FakeReadonlyTemporaryWorkspace(rootDirectory = worktree.rootDirectory.asFilesystemEntity)
    val taskCompleter = FakeTaskCompleter(TaskCompletionResult.Success(fakeWorkspace))

    val gitCloner = WrkGitCloner { _, _ -> worktree }
    val apiClient = WrkFakeApiClient()
    var publishedPrUrl: String? = null

    val processor =
        WrkProperSessionProcessor(
            gitCloner = gitCloner,
            taskCompleter = taskCompleter,
            publisher =
                WrkPublisher { _, _, _ ->
                  "https://github.com/acme/app/pull/1".also { publishedPrUrl = it }
                },
            log = {},
        )

    processor.process(session = testSession(), apiClient = apiClient)

    assertEquals(1, taskCompleter.observedPhases)
    assertTrue(fakeWorkspace.closed)
    assertEquals(
        listOf(
            WrkFakeApiClient.RecordedCall.CompleteSession(
                "s1",
                "https://github.com/acme/app/pull/1",
            )
        ),
        apiClient.recordedCalls.filterIsInstance<WrkFakeApiClient.RecordedCall.CompleteSession>(),
    )
    assertEquals("https://github.com/acme/app/pull/1", publishedPrUrl)
  }

  @Test
  fun `a clone failure fails the session with the clone error, without running the engine`() =
      runBlocking {
        val taskCompleter =
            FakeTaskCompleter(
                TaskCompletionResult.Failure.JointOperation(
                    phase = JointOperationPhase.ProjectBootstrapping,
                    operationFailure =
                        JointResult.Failure(
                            failureByModulePath = mapOf(fakeModulePath() to fakeModuleFailure())
                        ),
                ),
            )

        val gitCloner = WrkGitCloner { _, _ -> error("simulated clone failure") }
        val apiClient = WrkFakeApiClient()

        val processor =
            WrkProperSessionProcessor(
                gitCloner = gitCloner,
                taskCompleter = taskCompleter,
                publisher = WrkPublisher { _, _, _ -> error("must not be called") },
                log = {},
            )

        processor.process(session = testSession(), apiClient = apiClient)

        assertEquals(0, taskCompleter.observedPhases)

        val failCall =
            apiClient.recordedCalls
                .filterIsInstance<WrkFakeApiClient.RecordedCall.FailSession>()
                .single()
        assertEquals("s1", failCall.sessionId)
        assertTrue(failCall.failureSummary.contains("simulated clone failure"))
      }

  @Test
  fun `an engine failure fails the session with a module diagnostic summary`() = runBlocking {
    val taskCompleter =
        FakeTaskCompleter(
            TaskCompletionResult.Failure.AttemptsExhausted(
                attemptsMade = 5,
                lastHealthStatus =
                    ProjectHealthStatus.Unhealthy(
                        failureReport =
                            ProjectFailureReport(
                                stage = ProjectFailureReport.Stage.Testing,
                                failure =
                                    JointResult.Failure(
                                        failureByModulePath =
                                            mapOf(fakeModulePath() to fakeModuleFailure()),
                                    ),
                            ),
                    ),
            ),
        )

    val worktree = tempGitWorktree()
    val gitCloner = WrkGitCloner { _, _ -> worktree }
    val apiClient = WrkFakeApiClient()

    val processor =
        WrkProperSessionProcessor(
            gitCloner = gitCloner,
            taskCompleter = taskCompleter,
            publisher = WrkPublisher { _, _, _ -> error("must not be called") },
            log = {},
        )

    processor.process(session = testSession(), apiClient = apiClient)

    val failCall =
        apiClient.recordedCalls
            .filterIsInstance<WrkFakeApiClient.RecordedCall.FailSession>()
            .single()
    assertEquals("s1", failCall.sessionId)
    assertTrue(failCall.failureSummary.contains("Still unhealthy after 5"))
    assertTrue(failCall.failureSummary.contains("boom diagnostic"))
  }
}

private fun fakeModulePath() = software.medusa.commons.unix.path.UfsAbsolutePath.Root

private fun fakeModuleFailure() =
    software.medusa.flow.universal_project.UnpModuleConnection.Result.Failure(
        diagnosticOutput = "boom diagnostic",
    )
