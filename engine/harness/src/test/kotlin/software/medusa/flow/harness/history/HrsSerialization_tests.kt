package software.medusa.flow.harness.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import software.medusa.flow.harness.leadership.HrsLeaderCommand
import software.medusa.flow.harness.leadership.HrsRawLeaderCommand
import software.medusa.flow.harness.leadership.HrsTaskDefinition

class HrsSerialization_tests {
  private val json = Json

  @Test
  fun `a delegation report round-trips through JSON`() {
    val report =
        HrsDelegationReport(
            outcome = HrsDelegationOutcome.PartiallyDone,
            narrative = "wired the seam",
            filesTouched = "- /src/Main.kt — entrypoint",
            bufferChanges = "exposed /src/Api.kt",
            checksSummary = "green after 2 bounces",
            surprises = "Api couples to Db",
            leaderNotices = "the manifest omits the worker module",
        )

    assertEquals(report, json.decodeFromString<HrsDelegationReport>(json.encodeToString(report)))
  }

  @Test
  fun `a report omitting the optional fields round-trips`() {
    val report =
        HrsDelegationReport(
            outcome = HrsDelegationOutcome.Done,
            narrative = "n",
            filesTouched = "f",
            bufferChanges = "b",
            checksSummary = "c",
        )

    val decoded = json.decodeFromString<HrsDelegationReport>(json.encodeToString(report))
    assertEquals(report, decoded)
    assertEquals("", decoded.surprises)
    assertEquals("", decoded.leaderNotices)
  }

  @Test
  fun `a raw leader command round-trips through JSON`() {
    val raw =
        HrsRawLeaderCommand(
            stop = false,
            taskDefinition = "orient in /src",
            hideList = listOf("/a", "/b"),
        )

    assertEquals(raw, json.decodeFromString<HrsRawLeaderCommand>(json.encodeToString(raw)))
  }

  @Test
  fun `raw-command conversion round-trips both command shapes`() {
    val delegate =
        HrsLeaderCommand.Delegate(
            taskDefinition = HrsTaskDefinition("do the thing"),
            hideList = listOf("/x"),
        )

    assertEquals(delegate, HrsRawLeaderCommand.fromCommand(delegate).toCommand())
    assertEquals(
        HrsLeaderCommand.Stop,
        HrsRawLeaderCommand.fromCommand(HrsLeaderCommand.Stop).toCommand(),
    )
  }

  @Test
  fun `a stopping raw command ignores its other fields`() {
    val raw =
        HrsRawLeaderCommand(stop = true, taskDefinition = "ignored", hideList = listOf("/ignored"))

    assertEquals(HrsLeaderCommand.Stop, raw.toCommand())
  }
}
