package software.medusa.flow.virtual_editor.worktree

/**
 * The second visibility axis on an opened file (the first being open/closed). Exposure controls
 * whether the file is on the *leader's* board — its current content rendered into the leader's view
 * — as opposed to merely being remembered in the branch journal the assistant carries.
 *
 * Modeled as a sealed hierarchy rather than a `Boolean` so it stays open to the M4 masking
 * refinement (covering irrelevant *regions* of an exposed file), where a third case would carry
 * per-region state. Opening a file does not expose it: the default is [Hidden].
 */
sealed interface VedExposure {
  /** On the leader's board: the file's current content renders into the leader view. */
  data object Exposed : VedExposure

  /** Remembered but off the leader's board: renders as a one-line stub in the leader view. */
  data object Hidden : VedExposure
}
