package software.medusa.flow.server

import org.slf4j.LoggerFactory

/**
 * Operator-settable priority for the `flow:ready` queue, carried by `priority:*` labels ([label]).
 * [ReconcilePicker] orders candidates by [rank] ascending (urgent first), oldest-first within a
 * tier. Unlabeled ready issues are [Medium] — the implicit default.
 */
enum class IssuePriority(
    val rank: Int,
    val label: String,
) {
  Urgent(0, "priority:urgent"),
  High(1, "priority:high"),
  Medium(2, "priority:medium"),
  Low(3, "priority:low");

  companion object {
    private val log = LoggerFactory.getLogger(IssuePriority::class.java)

    /**
     * Reads an issue's priority off its label set: no `priority:*` label → [Medium]; more than one
     * (a labeling misconfiguration) → the highest, logged as a warning.
     */
    fun of(labels: Set<String>): IssuePriority {
      val matches = entries.filter { it.label in labels }
      if (matches.size > 1) {
        log.warn(
            "multiple priority labels on one issue: {} — using the highest",
            matches.map { it.label },
        )
      }
      return matches.minByOrNull { it.rank } ?: Medium
    }
  }
}
