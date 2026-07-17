package software.medusa.flow.virtual_editor.worktree

/**
 * The exposed-content budget gauge shown to both roles. It steers behaviour (the assistant keeps
 * the leader's board lean; the leader spends task-definition lines on cleanup when it runs high) —
 * it does not bill, so a cheap chars/[CHARS_PER_TOKEN] approximation is deliberate.
 */
data class VedExposureMeter(
    val exposedFileCount: Int,
    val approximateTokenCount: Int,
) {
  /**
   * A one-line header, e.g. `Leader buffer: ~14.2k tokens across 7 exposed files (soft budget:
   * 20k).`. [softBudgetTokens] is a parameter, never a constant buried in a prompt.
   */
  fun render(
      softBudgetTokens: Int,
  ): String {
    val files = if (exposedFileCount == 1) "1 exposed file" else "$exposedFileCount exposed files"
    return "Leader buffer: ~${formatCount(approximateTokenCount)} tokens across $files " +
        "(soft budget: ${formatCount(softBudgetTokens)})."
  }

  companion object {
    /** Characters per approximate token — the usual coarse rule of thumb. */
    const val CHARS_PER_TOKEN: Int = 4

    /** Approximates the token count of a run of text. */
    fun approximateTokens(
        characterCount: Int,
    ): Int = characterCount / CHARS_PER_TOKEN

    /**
     * Meters the exposed files of [worktree] — approximate tokens across their current contents.
     */
    fun of(
        worktree: VedWorktree,
    ): VedExposureMeter {
      val exposedFiles =
          worktree
              .visitOpenedFiles()
              .map { it.openedFile }
              .filter { it.exposure == VedExposure.Exposed }
              .toList()

      val characterCount = exposedFiles.sumOf { it.currentContent.dump().length }

      return VedExposureMeter(
          exposedFileCount = exposedFiles.size,
          approximateTokenCount = approximateTokens(characterCount = characterCount),
      )
    }

    /**
     * Formats a count compactly and *locale-independently* (byte-stable across machines/CI): plain
     * below 1000, otherwise thousands with a single decimal, dropping a trailing `.0` — `14200` →
     * `14.2k`, `20000` → `20k`, `512` → `512`.
     */
    fun formatCount(
        count: Int,
    ): String {
      if (count < 1000) return count.toString()

      val whole = count / 1000
      val tenths = (count % 1000) / 100

      return if (tenths == 0) "${whole}k" else "$whole.${tenths}k"
    }
  }
}
