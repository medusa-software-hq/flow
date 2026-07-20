package software.medusa.flow.worker

/**
 * GitHub auto-closes an issue when a merged PR's body (or a commit message) contains a *closing
 * keyword* immediately before a reference to it — `Closes #12`, `Fixes owner/repo#12`, `Resolves
 * https://github.com/owner/repo/issues/12`, and the close/closed/fix/fixed/resolve/… variants.
 *
 * Flow must never let that happen: closing an issue is the reconciler's exclusive, gated move (it
 * fires only after the merge checks pass). But an issue-linked PR body embeds the task Markdown,
 * which is the issue's own title + body — and that body may itself contain such a keyword before a
 * reference to another issue. This neutralizes any it finds.
 */
internal object WrkClosingKeywords {
  // A closing keyword, then optional colon/whitespace, then an issue reference: `#N`,
  // `owner/repo#N`, or a github issue URL.
  private val closingReferenceRegex =
      Regex(
          """(?i)\b(close[sd]?|fix(?:es|ed)?|resolve[sd]?)(\s*:?\s*)""" +
              """(#\d+|[\w.-]+/[\w.-]+#\d+|https?://github\.com/[\w.-]+/[\w.-]+/issues/\d+)""",
      )

  // Zero-width space: invisible when rendered, but it breaks GitHub's keyword recognition.
  private const val zeroWidthSpace = '​'

  /**
   * Returns [markdown] with every closing-keyword-before-a-reference neutralized (a zero-width
   * space inserted into the keyword), leaving the rendered text visually unchanged. Other text —
   * including a bare `Refs #N` — is untouched.
   */
  fun neutralize(
      markdown: String,
  ): String =
      closingReferenceRegex.replace(markdown) { match ->
        val keyword = match.groupValues[1]
        val neutralizedKeyword = "${keyword.first()}$zeroWidthSpace${keyword.substring(1)}"
        neutralizedKeyword + match.groupValues[2] + match.groupValues[3]
      }

  /** Whether [text] still contains a closing keyword immediately before an issue reference. */
  fun containsClosingReference(
      text: String,
  ): Boolean = closingReferenceRegex.containsMatchIn(text)
}
