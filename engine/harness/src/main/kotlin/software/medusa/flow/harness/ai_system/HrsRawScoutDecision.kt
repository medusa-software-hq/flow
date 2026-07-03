package software.medusa.flow.harness.ai_system

import kotlinx.schema.Description
import kotlinx.serialization.Serializable
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.ai_system.HrsScoutDecisionInterpreter.Decision
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedEntityAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment

/**
 * The raw, LLM-facing shape of a scouting decision: whether scouting is finished and, if not, which
 * closed files to open and which collapsed directories to expand.
 *
 * The scout-decision interpreter asks a model to distil the frontline's free-form scout message
 * into this shape; [toDecision] then turns the flat path lists into the structured [Decision] the
 * driver acts on, merging shared path prefixes into a single adjustment tree.
 */
@Serializable
internal data class HrsRawScoutDecision(
    @Description(
        "True when every file relevant to The Task is already open and scouting can finish.",
    )
    val scoutingComplete: Boolean,
    @Description(
        "Absolute paths of currently-closed files to open, exactly as shown in the worktree, e.g. `/src/Main.kt`.",
    )
    val filesToOpen: List<String>,
    @Description(
        "Absolute paths of collapsed directories to expand, exactly as shown in the worktree, e.g. `/src`.",
    )
    val directoriesToExpand: List<String>,
) {
  private data class LeafEntry(
      val names: List<UfsName.Literal>,
      val adjustment: VedEntityAdjustment,
  )

  fun toDecision(): Decision {
    val leafEntries =
        filesToOpen.map { path ->
          LeafEntry(names = parsePath(path), adjustment = VedFileAdjustment.Open)
        } +
            directoriesToExpand.map { path ->
              // The model tends to write directory paths with a trailing slash (`/app/src/`), which
              // would parse into an empty final name.
              LeafEntry(
                  names = parsePath(path.removeSuffix("/")),
                  adjustment = VedDirectoryAdjustment.Expand,
              )
            }

    // Nothing left to do — treat an empty request as readiness, so scouting always terminates.
    if (scoutingComplete || leafEntries.isEmpty()) {
      return Decision.Stop
    }

    return Decision.Continue(
        requestedAdjustment =
            VedWorktreeAdjustment(
                rootDirectoryAdjustment = buildDiveAdjustment(leafEntries = leafEntries)
            ),
    )
  }

  private fun parsePath(
      path: String,
  ): List<UfsName.Literal> {
    val literalPath =
        UfsAbsolutePath.parse(path).toLiteral()
            ?: throw IllegalArgumentException("Path `$path` is not a valid literal absolute path.")

    val names = literalPath.innerPath.names

    require(names.isNotEmpty()) { "Path `$path` does not point to an entity." }

    return names
  }

  private fun buildDiveAdjustment(
      leafEntries: List<LeafEntry>,
  ): VedDirectoryAdjustment.Dive {
    val childAdjustmentByName: Map<UfsName.Literal, VedEntityAdjustment> =
        leafEntries
            .groupBy { entry -> entry.names.first() }
            .mapValues { (_, group) ->
              val leafEntry = group.firstOrNull { entry -> entry.names.size == 1 }

              if (leafEntry != null) {
                leafEntry.adjustment
              } else {
                buildDiveAdjustment(
                    leafEntries = group.map { entry -> entry.copy(names = entry.names.drop(1)) },
                )
              }
            }

    return VedDirectoryAdjustment.Dive(childAdjustmentByName = childAdjustmentByName)
  }
}
