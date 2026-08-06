package software.medusa.flow.cli

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlin.test.Test
import kotlin.test.assertTrue
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.flow.harness.HrsPipelinePhase
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.history.HrsDelegationOutcome
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.harness.leadership.HrsTaskDefinition
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult

class CliTaskObserverTest {
  private fun recordingObserver(
      debug: Boolean = false,
  ): Pair<CliTaskObserver, TerminalRecorder> {
    val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE)
    val terminal = Terminal(ansiLevel = AnsiLevel.NONE, terminalInterface = recorder)
    return CliTaskObserver(terminal = terminal, debug = debug) to recorder
  }

  private val anyReport =
      HrsDelegationReport(
          outcome = HrsDelegationOutcome.Done,
          narrative = "did the thing\nmore detail",
          filesTouched = "- /a.txt — edited",
          bufferChanges = "none",
          checksSummary = "green",
      )

  @Test
  fun `a delegation prints its headline, then the report's outcome and narrative headline`() {
    val (observer, recorder) = recordingObserver()

    observer.observeDelegationStarted(HrsTaskDefinition(markdown = "Do the thing.\nMore detail."))
    observer.observeDelegationReport(anyReport)

    val output = recorder.output()
    assertTrue(output.contains("Delegating: Do the thing."), output)
    assertTrue(output.contains("done"), output)
    assertTrue(output.contains("did the thing"), output)
    // The narrative's second line is not the headline and must not leak through.
    assertTrue(!output.contains("more detail"), output)
  }

  @Test
  fun `debug-only detail is suppressed by default and shown once debug is on`() {
    val (quietObserver, quietRecorder) = recordingObserver(debug = false)
    quietObserver.observeDelegationReport(anyReport)
    assertTrue(!quietRecorder.output().contains("checks:"), quietRecorder.output())

    val (debugObserver, debugRecorder) = recordingObserver(debug = true)
    debugObserver.observeDelegationReport(anyReport)
    assertTrue(debugRecorder.output().contains("checks:"), debugRecorder.output())
    assertTrue(debugRecorder.output().contains("/a.txt"), debugRecorder.output())
  }

  @Test
  fun `a healthy gate result prints green, an unhealthy one prints its diagnostics only in debug`() {
    val (observer, recorder) = recordingObserver()
    observer.observeGateResult(ProjectHealthStatus.Healthy)
    assertTrue(recorder.output().contains("Gate: healthy"), recorder.output())

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

    val (debugObserver, debugRecorder) = recordingObserver(debug = true)
    debugObserver.observeGateResult(ProjectHealthStatus.Unhealthy(failureReport = failure))
    assertTrue(debugRecorder.output().contains("Gate: unhealthy"), debugRecorder.output())
    assertTrue(debugRecorder.output().contains("boom"), debugRecorder.output())
  }

  @Test
  fun `phase boundaries render a headline`() {
    val (observer, recorder) = recordingObserver()
    observer.observePhase(HrsPipelinePhase.HealthGate)
    assertTrue(recorder.output().contains("Running initial health gate"), recorder.output())
  }
}
