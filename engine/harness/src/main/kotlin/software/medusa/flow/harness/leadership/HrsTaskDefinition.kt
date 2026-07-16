package software.medusa.flow.harness.leadership

import kotlinx.serialization.Serializable

/**
 * The leader→assistant instrument: a natural-language, Markdown task definition for a single
 * delegation. The leader steers entirely through these — orientation, planning-by-doing,
 * implementation and cleanup are all just delegations with different definitions (there are no
 * phases). Definitions are encouraged to carry exposure guidance ("expose the interfaces you find,
 * not implementations") and check expectations.
 */
@Serializable
@JvmInline
value class HrsTaskDefinition(
    val markdown: String,
)
