package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.history.HrsChunkSummary
import software.medusa.flow.harness.history.HrsChunkSummaryKind
import software.medusa.flow.harness.history.HrsDelegationOutcome
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.harness.leadership.HrsTaskDefinition
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.v1.SessionEventKind

/**
 * The leader/assistant engine's hooks (M3-11): delegations report as [SessionEventKind]
 * DELEGATION/DELEGATION_REPORT, its gate result reuses HEALTH_CHECK, and compaction never reaches
 * the wire at all.
 */
class WrkReportingTaskObserver_tests {
  private fun observerWithFakeClient(): Pair<WrkReportingTaskObserver, WrkFakeApiClient> {
    val apiClient = WrkFakeApiClient()
    val observer = WrkReportingTaskObserver(sessionId = "s1", apiClient = apiClient, log = {})
    return observer to apiClient
  }

  private val anyReport =
      HrsDelegationReport(
          outcome = HrsDelegationOutcome.Done,
          narrative = "did the thing",
          filesTouched = "- /a.txt — edited",
          bufferChanges = "none",
          checksSummary = "green",
      )

  @Test
  fun `a delegation starting reports its task definition's headline only`() {
    val (observer, apiClient) = observerWithFakeClient()

    observer.observeDelegationStarted(HrsTaskDefinition(markdown = "Do the thing.\nMore detail."))

    val call = apiClient.recordedCalls.single() as WrkFakeApiClient.RecordedCall.AppendSessionEvent
    assertEquals(SessionEventKind.SESSION_EVENT_KIND_DELEGATION, call.kind)
    assertEquals("Do the thing.", call.message)
  }

  @Test
  fun `a delegation report renders its outcome, narrative, files, and checks as one Markdown block`() {
    val (observer, apiClient) = observerWithFakeClient()

    observer.observeDelegationReport(anyReport)

    val call = apiClient.recordedCalls.single() as WrkFakeApiClient.RecordedCall.AppendSessionEvent
    assertEquals(SessionEventKind.SESSION_EVENT_KIND_DELEGATION_REPORT, call.kind)
    assertTrue(call.message.contains("Done"), call.message)
    assertTrue(call.message.contains("did the thing"), call.message)
    assertTrue(call.message.contains("/a.txt"), call.message)
    assertTrue(call.message.contains("green"), call.message)
    // No surprises on this report -- the optional block is omitted entirely, not left blank.
    assertFalse(call.message.contains("Surprises"), call.message)
  }

  @Test
  fun `a delegation report's surprises render only when present`() {
    val (observer, apiClient) = observerWithFakeClient()

    observer.observeDelegationReport(anyReport.copy(surprises = "found a stray TODO"))

    val call = apiClient.recordedCalls.single() as WrkFakeApiClient.RecordedCall.AppendSessionEvent
    assertTrue(call.message.contains("Surprises"), call.message)
    assertTrue(call.message.contains("found a stray TODO"), call.message)
  }

  @Test
  fun `the leader-assistant engine's gate result reuses HEALTH_CHECK for both a healthy and an unhealthy verdict`() {
    val (observer, apiClient) = observerWithFakeClient()

    observer.observeGateResult(ProjectHealthStatus.Healthy)

    val rootModulePath = checkNotNull(UfsAbsolutePath.parse("/").toLiteral())
    val failure =
        ProjectFailureReport(
            stage = ProjectFailureReport.Stage.Analysis,
            failure =
                JointResult.Failure(
                    failureByModulePath =
                        mapOf(
                            rootModulePath to
                                UnpModuleConnection.Result.Failure(diagnosticOutput = "boom"),
                        ),
                ),
        )
    observer.observeGateResult(ProjectHealthStatus.Unhealthy(failureReport = failure))

    val calls =
        apiClient.recordedCalls.filterIsInstance<WrkFakeApiClient.RecordedCall.AppendSessionEvent>()
    assertEquals(2, calls.size)
    assertTrue(calls.all { it.kind == SessionEventKind.SESSION_EVENT_KIND_HEALTH_CHECK })
    assertTrue(calls[0].message.contains("passed"), calls[0].message)
    assertTrue(calls[1].message.contains("boom"), calls[1].message)
  }

  @Test
  fun `compaction never reaches the wire, only the debug log`() {
    val apiClient = WrkFakeApiClient()
    val logged = mutableListOf<String>()
    val observer =
        WrkReportingTaskObserver(sessionId = "s1", apiClient = apiClient, log = logged::add)

    observer.observeCompaction(
        kind = HrsChunkSummaryKind.SmallChunk,
        delegationRange = 0..2,
        summary = HrsChunkSummary(markdown = "summary"),
    )

    assertTrue(apiClient.recordedCalls.isEmpty())
    assertTrue(logged.single().contains("compacted"), logged.toString())
  }
}
