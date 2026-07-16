package software.medusa.flow.harness.history

import kotlinx.schema.Description
import kotlinx.serialization.Serializable

/**
 * A delegation's report — simultaneously the leader's *only* window into what happened, the tier-0
 * history entry, and the seed for compaction. The assistant writes it as the terminal `done` tool
 * call, so this type *is* that tool's argument schema (no separate summarization step, no
 * interpreter).
 *
 * Every prose field is Markdown. [surprises] and [leaderNotices] are optional (empty when there is
 * nothing to say).
 */
@Serializable
data class HrsDelegationReport(
    @Description("The headline verdict: Done, PartiallyDone, or Failed.")
    val outcome: HrsDelegationOutcome,
    @Description("Short prose: what was done and the decisions taken.") val narrative: String,
    @Description(
        "Paths touched, each with a one-line note, e.g. `- /src/Main.kt — added the entrypoint`."
    )
    val filesTouched: String,
    @Description("What was exposed or hidden on the leader board and why, one line each.")
    val bufferChanges: String,
    @Description("Final gate status, and what was fought through to reach it.")
    val checksSummary: String,
    @Description(
        "Anything the leader should know: unexpected coupling, suspected bugs, manifest gaps. Empty if none.",
    )
    val surprises: String = "",
    @Description(
        "Facts from the branch record that appear absent from the leader's current framing and are material to the task. Facts, not strategy. Empty if none.",
    )
    val leaderNotices: String = "",
)
