package software.medusa.flow.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings' `auto_merge`, armed by [ReconcileObserver] on the primary PR while it's still
 * `PR_OPEN` (see `ReconcileObserver.armAutoMergeIfEnabled`). Separate from
 * [ReconcileObserver_tests] so this file only relies on the current, shadow-session-aware
 * [IssuePipelineStore.pick] signature.
 */
class ReconcileObserverAutoMerge_tests {
  private val repo = "acme/app"

  private class Fixture {
    val backend = InMemoryPipelineBackend()
    val pipelines = InMemoryIssuePipelineStore(backend)
    val sessions = InMemorySessionStore()
    val prClient = FakeGitHubPrClient()
    val settings = InMemorySettingsStore()
    val observer = ReconcileObserver(pipelines, sessions, prClient, settingsStore = settings)
  }

  /** Picks an issue and drives it to `PR_OPEN` (pr #7). */
  private suspend fun Fixture.pipelineInPrOpen(): IssuePipeline {
    val picked =
        (pipelines.pick(
                repo,
                1,
                "Issue 1",
                "https://x/1",
                SessionId("primary"),
                SessionId("shadow"),
            ) as PickResult.Picked)
            .pipeline
    pipelines.markPrOpen(picked.id, prNumber = 7, prUrl = "https://x/pr/7")
    return pipelines.get(picked.id)!!
  }

  @Test
  fun `auto-merge on arms the primary PR while it is still open`() = runBlocking {
    val fx = Fixture()
    fx.settings.update(FlowSettings(autoMerge = true))
    fx.pipelineInPrOpen()

    assertEquals(0, fx.observer.observe(repo)) // arming isn't itself a pipeline-state transition
    assertEquals(listOf(7 to MergeMethod.Merge), fx.prClient.autoMergeCalls)
  }

  @Test
  fun `auto-merge off never arms`() = runBlocking {
    val fx = Fixture() // default: autoMerge = false
    fx.pipelineInPrOpen()

    fx.observer.observe(repo)

    assertTrue(fx.prClient.autoMergeCalls.isEmpty())
  }

  @Test
  fun `a repo that rejects auto-merge degrades gracefully`() = runBlocking {
    val fx = Fixture()
    fx.settings.update(FlowSettings(autoMerge = true))
    val p = fx.pipelineInPrOpen()
    fx.prClient.autoMergeResultByNumber[7] =
        AutoMergeResult.Failed("Auto-merge is not enabled for this repository")

    // No throw, no advance — the pipeline still merge-watches normally.
    assertEquals(0, fx.observer.observe(repo))
    assertEquals(IssuePipelineState.PrOpen, fx.pipelines.get(p.id)!!.state)
    assertEquals(listOf(7 to MergeMethod.Merge), fx.prClient.autoMergeCalls)
  }

  @Test
  fun `auto-merge is not attempted once the PR has merged`() = runBlocking {
    val fx = Fixture()
    fx.settings.update(FlowSettings(autoMerge = true))
    fx.pipelineInPrOpen()
    fx.prClient.prStateByNumber[7] = PullRequestState.Merged("deadbeef")

    fx.observer.observe(repo)

    assertTrue(fx.prClient.autoMergeCalls.isEmpty())
  }
}
