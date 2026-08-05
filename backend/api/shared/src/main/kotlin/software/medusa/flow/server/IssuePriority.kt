package software.medusa.flow.server

/**
 * Operator-settable priority for the `flow:ready` queue.
 *
 * The sole source is the native GitHub **Issue Field** `Priority` (a single-select field; public
 * preview as of 2026-03-12 —
 * https://github.blog/changelog/2026-03-12-issue-fields-structured-issue-metadata-is-in-public-preview/),
 * read via [fieldValue] and fetched unconditionally by [GitHubAppCandidateClient].
 *
 * [ReconcilePicker] orders candidates by [rank] ascending (urgent first), oldest-first within a
 * tier. An issue with no field value (unset, or the field read degraded — see
 * [GitHubAppCandidateClient]) is [Medium] — the implicit default.
 */
enum class IssuePriority(
    val rank: Int,
    /** The `Priority` Issue Field's single-select option name, matched case-insensitively. */
    val fieldValue: String,
) {
  Urgent(0, "Urgent"),
  High(1, "High"),
  Medium(2, "Medium"),
  Low(3, "Low");

  companion object {
    /**
     * Reads an issue's priority from the `Priority` Issue Field's selected option name
     * ([fieldValue]). `null` or an unrecognized value is [Medium] — the implicit default.
     */
    fun of(
        fieldValue: String?,
    ): IssuePriority =
        fieldValue?.let { v -> entries.find { it.fieldValue.equals(v, ignoreCase = true) } }
            ?: Medium
  }
}
