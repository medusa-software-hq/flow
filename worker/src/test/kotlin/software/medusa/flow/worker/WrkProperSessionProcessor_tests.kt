package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
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
import software.medusa.flow.harness.UnimplementedHrsTaskCompleter
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.v1.Engine
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
  var capturedTask: HrsTaskDescription? = null

  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult {
    observer.observePhase(software.medusa.flow.harness.HrsPipelinePhase.WorkspacePreparing)
    observedPhases++
    capturedTask = taskDescription
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
  fun `an aborted heartbeat cancels the engine and publishes nothing`() = runBlocking {
    val worktree = tempGitWorktree()

    // An engine that blocks until cancelled, so the abort has a live run to tear down.
    val blockingCompleter =
        object : HrsTaskCompleter {
          override suspend fun completeTask(
              sourceGitWorktree: GitWorktree,
              taskDescription: HrsTaskDescription,
              observer: Observer,
          ): TaskCompletionResult {
            observer.observePhase(software.medusa.flow.harness.HrsPipelinePhase.WorkspacePreparing)
            awaitCancellation()
          }
        }

    val apiClient = WrkFakeApiClient().apply { abortOnHeartbeat = true }
    var published = false

    val processor =
        WrkProperSessionProcessor(
            gitCloner = WrkGitCloner { _, _ -> worktree },
            engineResolver = builtinResolver(blockingCompleter),
            publisher =
                WrkPublisher { _, _, _, _, _, _, _, _ ->
                  published = true
                  WrkPublishResult.Published(prUrl = "https://github.com/acme/app/pull/1")
                },
            heartbeatIntervalMillis = 10,
            log = {},
        )

    // Returns (doesn't hang) because the ABORTED heartbeat cancels the engine job.
    processor.process(session = testSession(), apiClient = apiClient)

    assertFalse(published, "an aborted session must not publish a PR")
    assertTrue(
        apiClient.recordedCalls.none { it is WrkFakeApiClient.RecordedCall.CompleteSession },
        "an aborted session must not be completed",
    )
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
            engineResolver = builtinResolver(taskCompleter),
            publisher =
                WrkPublisher { _, _, _, _, _, _, _, _ ->
                  WrkPublishResult.Published(prUrl = "https://github.com/acme/app/pull/1").also {
                    publishedPrUrl = it.prUrl
                  }
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
  fun `the whole task body — not just the intro-less lead — reaches the engine`() = runBlocking {
    // A real issue: its substance lives entirely in `##` sub-sections, with no intro paragraph
    // directly under the `#` title. Rendering only the root chapter's lead element (the old bug)
    // yields an empty prompt for exactly this shape — the engine then sees no task and does
    // nothing (see the garbage PR #136 on the "Remove the Demo" run).
    val markdown =
        """
        # Remove the Demo

        ## Goal

        Delete the counter widget and the embedded issue list from the landing page.

        ## Definition of done

        - [ ] No Demo nav item; `/` lands on Sessions.
        """
            .trimIndent()

    val worktree = tempGitWorktree()
    val fakeWorkspace =
        FakeReadonlyTemporaryWorkspace(rootDirectory = worktree.rootDirectory.asFilesystemEntity)
    val taskCompleter = FakeTaskCompleter(TaskCompletionResult.Success(fakeWorkspace))

    val processor =
        WrkProperSessionProcessor(
            gitCloner = WrkGitCloner { _, _ -> worktree },
            engineResolver = builtinResolver(taskCompleter),
            publisher =
                WrkPublisher { _, _, _, _, _, _, _, _ ->
                  WrkPublishResult.Published(prUrl = "https://github.com/acme/app/pull/1")
                },
            log = {},
        )

    processor.process(
        session =
            session {
              id = "s1"
              repoFullName = "acme/app"
              taskMarkdown = markdown
            },
        apiClient = WrkFakeApiClient(),
    )

    val renderedBody = taskCompleter.capturedTask!!.body.render()
    assertTrue(renderedBody.contains("## Goal"), "the Goal section must survive: $renderedBody")
    assertTrue(
        renderedBody.contains("Delete the counter widget"),
        "the Goal body must survive: $renderedBody",
    )
    assertTrue(
        renderedBody.contains("Definition of done"),
        "later sections must survive: $renderedBody",
    )
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
                engineResolver = builtinResolver(taskCompleter),
                publisher = WrkPublisher { _, _, _, _, _, _, _, _ -> error("must not be called") },
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
            engineResolver = builtinResolver(taskCompleter),
            publisher = WrkPublisher { _, _, _, _, _, _, _, _ -> error("must not be called") },
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

  @Test
  fun `dispatch routes each session to the completer for its engine, defaulting when unspecified`() =
      runBlocking {
        val builtin = RecordingTaskCompleter()
        val claude = RecordingTaskCompleter()
        val leader = RecordingTaskCompleter()
        val resolver = WrkEngineResolver(builtin = builtin, claude = claude, leader = leader)

        fun run(engine: Engine) = runBlocking {
          val worktree = tempGitWorktree()
          val processor =
              WrkProperSessionProcessor(
                  gitCloner = WrkGitCloner { _, _ -> worktree },
                  engineResolver = resolver,
                  publisher =
                      WrkPublisher { _, _, _, _, _, _, _, _ ->
                        WrkPublishResult.Published(prUrl = "https://github.com/acme/app/pull/1")
                      },
                  log = {},
              )
          processor.process(
              session =
                  session {
                    id = "s1"
                    repoFullName = "acme/app"
                    taskMarkdown = "# Do the thing"
                    this.engine = engine
                  },
              apiClient = WrkFakeApiClient(),
          )
        }

        run(Engine.ENGINE_CLAUDE)
        assertEquals(1, claude.runs)
        assertEquals(0, builtin.runs)
        assertEquals(0, leader.runs)

        run(Engine.ENGINE_BUILTIN)
        assertEquals(1, builtin.runs)

        run(Engine.ENGINE_LEADER)
        assertEquals(1, leader.runs)

        // UNSPECIFIED → the configured default (claude, reverting the M3-12 flip).
        run(Engine.ENGINE_UNSPECIFIED)
        assertEquals(1, builtin.runs)
        assertEquals(2, claude.runs)
        assertEquals(1, leader.runs)
      }
}

// Explicit `default = taskCompleter`: these tests use an unspecified-engine session and just want
// their one completer to run, independent of whatever WrkEngineResolver's own default is wired to.
private fun builtinResolver(
    taskCompleter: HrsTaskCompleter,
): WrkEngineResolver =
    WrkEngineResolver(
        builtin = taskCompleter,
        claude = UnimplementedHrsTaskCompleter(engineName = "claude"),
        leader = UnimplementedHrsTaskCompleter(engineName = "leader"),
        default = taskCompleter,
    )

/**
 * Records how many times it ran and returns a workspace-backed success (so publishing proceeds).
 */
private class RecordingTaskCompleter : HrsTaskCompleter {
  var runs = 0

  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult {
    runs++
    return TaskCompletionResult.Success(
        FakeReadonlyTemporaryWorkspace(
            rootDirectory = sourceGitWorktree.rootDirectory.asFilesystemEntity,
        ),
    )
  }
}

private fun fakeModulePath() = software.medusa.commons.unix.path.UfsAbsolutePath.Root

private fun fakeModuleFailure() =
    software.medusa.flow.universal_project.UnpModuleConnection.Result.Failure(
        diagnosticOutput = "boom diagnostic",
    )
