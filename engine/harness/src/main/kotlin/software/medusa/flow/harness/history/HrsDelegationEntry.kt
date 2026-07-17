package software.medusa.flow.harness.history

import software.medusa.flow.harness.leadership.HrsTaskDefinition

/**
 * One closed delegation as stored in the log: the leader's task definition and the assistant's
 * report. The delegation's *file snapshots* are not stored here — they already live in the virtual
 * editor's version history and are zipped in by timestamp when the journal is rendered (the journal
 * is a pure view, not a captured structure).
 */
data class HrsDelegationEntry(
    val taskDefinition: HrsTaskDefinition,
    val report: HrsDelegationReport,
)
