package software.medusa.flow.harness.history

import kotlinx.serialization.Serializable

/** The headline verdict of a delegation — the first thing the leader reads in a report. */
@Serializable
enum class HrsDelegationOutcome {
  /** The delegation's task definition was fully satisfied and the gate passed. */
  Done,

  /** Some progress made, but the task was not fully satisfied (the leader decides what next). */
  PartiallyDone,

  /** The delegation could not reach a good state (e.g. the gate never went green). */
  Failed,
}
