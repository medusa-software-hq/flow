package software.medusa.flow.server

import org.slf4j.LoggerFactory

/**
 * Operator-settable priority for the `flow:ready` queue.
 *
 * Authoritative source is the native GitHub **Issue Field** `Priority` (a single-select field;
 * public preview as of 2026-03-12 —
 * https://github.blog/changelog/2026-03-12-issue-fields-structured-issue-metadata-is-in-public-preview/),
 * read via [fieldValue]. The `priority:*` **labels** ([label]) are the predecessor representation,
 * kept as a fallback for the grace window while issues are migrated and while
 * [GitHubAppCandidateClient]'s Issue Fields read (`readPriorityField`) is unverified against a live
 * schema — see that class's KDoc. Once every repo's issues carry a field value and the labels are
 * retired from `github-labels.tf`, the label branch of [of] becomes permanently dead and can be
 * deleted along with [label].
 *
 * [ReconcilePicker] orders candidates by [rank] ascending (urgent first), oldest-first within a
 * tier. An issue with neither a field value nor a label is [Medium] — the implicit default.
 */
enum class IssuePriority(
    val rank: Int,
    val label: String,
    /** The `Priority` Issue Field's single-select option name, matched case-insensitively. */
    val fieldValue: String,
) {
  Urgent(0, "priority:urgent", "Urgent"),
  High(1, "priority:high", "High"),
  Medium(2, "priority:medium", "Medium"),
  Low(3, "priority:low", "Low");

  companion object {
    private val log = LoggerFactory.getLogger(IssuePriority::class.java)

    /**
     * Reads an issue's priority: [fieldValue] (the Issue Field's selected option name) wins when
     * present — it's the platform-enforced single value, so there's no multi-value case to resolve.
     * Otherwise falls back to [labels] for the grace window: no `priority:*` label → [Medium]; more
     * than one (a labeling misconfiguration, only possible pre-migration) → the highest, logged as
     * a warning.
     */
    fun of(
        fieldValue: String?,
        labels: Set<String>,
    ): IssuePriority {
      val fromField = fieldValue?.let { v ->
        entries.find { it.fieldValue.equals(v, ignoreCase = true) }
      }
      if (fromField != null) return fromField

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
