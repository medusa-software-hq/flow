package software.medusa.flow.harness.assistance

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.virtual_editor.VedTimestamp

/**
 * Builds the [HrsToolbox] a single delegation runs against — the gate ([HrsToolbox.checkGate]) and
 * worktree/physical-workspace bridge ([HrsToolbox.execute]) bound to this run's git worktree,
 * physical workspace, and project connection, stamped at a fresh [VedTimestamp] per delegation (see
 * [HrsProperToolbox]'s single-timestamp-per-thread contract).
 *
 * Exists so the executor wiring the leader and assistant together can construct a real
 * [HrsProperToolbox] in production while a test swaps in a [FakeHrsToolbox] without needing a real
 * git worktree, physical workspace, or project connection.
 */
fun interface HrsToolboxFactory {
  fun create(
      gitWorktree: GitWorktree,
      physicalRootDirectory: UfsMutableDirectory,
      projectConnection: UnpProjectConnection,
      delegationTimestamp: VedTimestamp,
  ): HrsToolbox
}
