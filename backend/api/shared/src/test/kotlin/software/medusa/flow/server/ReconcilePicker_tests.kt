package software.medusa.flow.server

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ReconcilePicker_tests {
  private val repo = "acme/app"

  private class Fixture {
    val backend = InMemoryPipelineBackend()
    val pipelines = InMemoryIssuePipelineStore(backend)
    val sessions = InMemorySessionStore()
    val candidates = FakeGitHubCandidateClient()
    val picker = ReconcilePicker(pipelines, sessions, candidates)
  }

  private fun candidate(
      number: Int,
      createdAt: String,
      title: String = "Issue $number",
      body: String = "Body $number",
      labels: Set<String> = emptySet(),
  ) =
      CandidateIssue(
          number = number,
          title = title,
          body = body,
          url = "https://x/$number",
          createdAt = Instant.parse(createdAt),
          labels = labels,
      )

  @Test
  fun `picks the oldest candidate and starts an issue-linked session`() = runBlocking {
    val fx = Fixture()
    fx.candidates.candidatesByRepo[repo] =
        listOf(
            candidate(2, "2026-05-02T00:00:00Z"),
            candidate(1, "2026-05-01T00:00:00Z"), // oldest
        )

    assertEquals(1, fx.picker.pick(repo))

    val pipeline = fx.pipelines.list(repo).single()
    assertEquals(1, pipeline.issueNumber) // oldest first
    assertEquals(IssuePipelineState.InProgress, pipeline.state)

    // The linked session is PENDING and claimable, with task = "# title\n\nbody".
    val session = fx.sessions.get(pipeline.sessionId!!, afterSeq = 0)!!.session
    assertEquals(SessionState.Pending, session.state)
    assertEquals("# Issue 1\n\nBody 1", session.taskMarkdown)
    assertEquals(repo, session.repoFullName)
  }

  @Test
  fun `pick fans out to a primary Claude session and a built-in shadow session`() = runBlocking {
    val fx = Fixture()
    // No flow:engine label matters any more — both engines always run.
    fx.candidates.candidatesByRepo[repo] =
        listOf(candidate(1, "2026-05-01T00:00:00Z", labels = setOf("flow:ready")))

    assertEquals(1, fx.picker.pick(repo))

    val pipeline = fx.pipelines.list(repo).single()
    val primary = fx.sessions.get(pipeline.sessionId!!, afterSeq = 0)!!.session
    val shadow = fx.sessions.get(pipeline.shadowSessionId!!, afterSeq = 0)!!.session

    // The primary drives the pipeline (Claude); the shadow runs unobserved (built-in).
    assertEquals(Engine.Claude, primary.engine)
    assertEquals(Engine.Builtin, shadow.engine)
    assertTrue(primary.id != shadow.id)
    // Both are PENDING/claimable and share the same task.
    assertEquals(SessionState.Pending, shadow.state)
    assertEquals("# Issue 1\n\nBody 1", shadow.taskMarkdown)
    // Exactly the two fan-out sessions were created.
    assertEquals(2, fx.sessions.list(limit = 100).size)
  }

  @Test
  fun `no candidates means no pick and no session`() = runBlocking {
    val fx = Fixture()

    assertEquals(0, fx.picker.pick(repo))
    assertTrue(fx.pipelines.list(repo).isEmpty())
    assertTrue(fx.sessions.list(limit = 100).isEmpty())
  }

  @Test
  fun `a busy repo (live pipeline) is not picked`() = runBlocking {
    val fx = Fixture()
    fx.pipelines.pick(repo, 99, "existing", "u", SessionId("existing")) // IN_PROGRESS → busy
    fx.candidates.candidatesByRepo[repo] = listOf(candidate(1, "2026-05-01T00:00:00Z"))

    assertEquals(0, fx.picker.pick(repo))
    assertEquals(1, fx.pipelines.list(repo).size) // only the pre-existing one
  }

  @Test
  fun `a non-cleared FAILED pipeline holds the mutex - no pick`() = runBlocking {
    val fx = Fixture()
    val existing =
        (fx.pipelines.pick(repo, 99, "existing", "u", SessionId("e")) as PickResult.Picked).pipeline
    fx.pipelines.markFailed(existing.id, "boom")
    fx.candidates.candidatesByRepo[repo] = listOf(candidate(1, "2026-05-01T00:00:00Z"))

    assertEquals(0, fx.picker.pick(repo))
  }

  @Test
  fun `clearing a FAILED pipeline releases the mutex and the next pick proceeds`() = runBlocking {
    val fx = Fixture()
    val existing =
        (fx.pipelines.pick(repo, 99, "existing", "u", SessionId("e")) as PickResult.Picked).pipeline
    fx.pipelines.markFailed(existing.id, "boom")
    fx.pipelines.clear(existing.id)
    fx.candidates.candidatesByRepo[repo] = listOf(candidate(1, "2026-05-01T00:00:00Z"))

    assertEquals(1, fx.picker.pick(repo))
    assertEquals(setOf(99, 1), fx.pipelines.list(repo).map { it.issueNumber }.toSet())
  }

  @Test
  fun `an issue that already has a non-cleared pipeline row is skipped`() = runBlocking {
    val fx = Fixture()
    // Issue 5 already ran to DONE (non-cleared, but not live → repo is free).
    val done =
        (fx.pipelines.pick(repo, 5, "five", "u", SessionId("s5")) as PickResult.Picked).pipeline
    fx.pipelines.markPrOpen(done.id, 1, "pr")
    fx.pipelines.markAwaitingMergeChecks(done.id, "sha")
    fx.pipelines.markDone(done.id, "done")

    // The candidate query still lists issue 5 (stale) plus a fresh issue 6.
    fx.candidates.candidatesByRepo[repo] =
        listOf(candidate(5, "2026-05-01T00:00:00Z"), candidate(6, "2026-05-02T00:00:00Z"))

    assertEquals(1, fx.picker.pick(repo))
    // Picked 6, not 5 (5 has a non-cleared row) — even though 5 is "older".
    assertTrue(
        fx.pipelines.list(repo).any {
          it.issueNumber == 6 && it.state == IssuePipelineState.InProgress
        }
    )
  }

  @Test
  fun `double invocation picks exactly once (the mutex blocks the second)`() = runBlocking {
    val fx = Fixture()
    fx.candidates.candidatesByRepo[repo] =
        listOf(candidate(1, "2026-05-01T00:00:00Z"), candidate(2, "2026-05-02T00:00:00Z"))

    assertEquals(1, fx.picker.pick(repo))
    assertEquals(0, fx.picker.pick(repo)) // repo now busy

    assertEquals(1, fx.pipelines.list(repo).size)
  }

  @Test
  fun `priority beats age - a newer urgent issue is picked over an older unlabeled one`() =
      runBlocking {
        val fx = Fixture()
        fx.candidates.candidatesByRepo[repo] =
            listOf(
                candidate(1, "2026-05-01T00:00:00Z"), // older, unlabeled (medium)
                candidate(2, "2026-05-02T00:00:00Z", labels = setOf("priority:urgent")), // newer
            )

        assertEquals(1, fx.picker.pick(repo))

        val pipeline = fx.pipelines.list(repo).single()
        assertEquals(2, pipeline.issueNumber)
      }

  @Test
  fun `within the same priority tier the oldest wins`() = runBlocking {
    val fx = Fixture()
    fx.candidates.candidatesByRepo[repo] =
        listOf(
            candidate(2, "2026-05-02T00:00:00Z", labels = setOf("priority:high")),
            candidate(1, "2026-05-01T00:00:00Z", labels = setOf("priority:high")), // oldest
        )

    assertEquals(1, fx.picker.pick(repo))

    val pipeline = fx.pipelines.list(repo).single()
    assertEquals(1, pipeline.issueNumber)
  }

  @Test
  fun `an unlabeled issue is treated as medium priority`() = runBlocking {
    val fx = Fixture()
    fx.candidates.candidatesByRepo[repo] =
        listOf(
            candidate(1, "2026-05-01T00:00:00Z", labels = setOf("priority:low")), // oldest, low
            candidate(2, "2026-05-02T00:00:00Z"), // newer, unlabeled → medium, beats low
        )

    assertEquals(1, fx.picker.pick(repo))

    val pipeline = fx.pipelines.list(repo).single()
    assertEquals(2, pipeline.issueNumber)
  }

  @Test
  fun `multiple priority labels on one issue resolve to the highest`() = runBlocking {
    val fx = Fixture()
    fx.candidates.candidatesByRepo[repo] =
        listOf(
            candidate(1, "2026-05-01T00:00:00Z", labels = setOf("priority:high")),
            // Misconfigured: carries both low and urgent — should resolve to urgent and win.
            candidate(
                2,
                "2026-05-02T00:00:00Z",
                labels = setOf("priority:low", "priority:urgent"),
            ),
        )

    assertEquals(1, fx.picker.pick(repo))

    val pipeline = fx.pipelines.list(repo).single()
    assertEquals(2, pipeline.issueNumber)
  }

  @Test
  fun `a blocked urgent issue is not among candidates and does not get picked`() = runBlocking {
    val fx = Fixture()
    // Eligibility (open + ready + no open blockers) is enforced upstream by the candidate client;
    // a still-blocked urgent issue never appears here, so the picker falls through to what's
    // actually eligible regardless of its (unseen) priority.
    fx.candidates.candidatesByRepo[repo] =
        listOf(candidate(1, "2026-05-01T00:00:00Z", labels = setOf("priority:low")))

    assertEquals(1, fx.picker.pick(repo))

    val pipeline = fx.pipelines.list(repo).single()
    assertEquals(1, pipeline.issueNumber)
  }
}
