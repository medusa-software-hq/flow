package software.medusa.flow.harness.history

import kotlinx.schema.Description
import kotlinx.serialization.Serializable

/**
 * The raw, LLM-facing shape of a chunk summary — a flat structured-output payload the still-open
 * assistant thread is asked for at chunk close (story 07). [toChunkSummary] turns it into the
 * storage type; same `HrsRaw*` convention as
 * [software.medusa.flow.harness.leadership.HrsRawLeaderCommand].
 */
@Serializable
data class HrsRawChunkSummary(
    @Description(
        "A dense Markdown summary of the closed chunk of delegations, written for a leader with " +
            "far less context than you: what was done, key decisions, files touched, and anything " +
            "that must not be lost now that the individual delegations are being compressed away.",
    )
    val markdown: String,
) {
  fun toChunkSummary(): HrsChunkSummary = HrsChunkSummary(markdown = markdown)
}
