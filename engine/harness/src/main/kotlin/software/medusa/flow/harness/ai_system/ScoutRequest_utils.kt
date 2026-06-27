package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.markdown.MdInlineNode
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutRequest
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedEntityAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment

/**
 * Codec between a [ScoutRequest] and its ad-hoc Markdown representation.
 *
 * A request renders as a rationale (free-form prose) followed by a single nested bullet list that
 * mirrors the worktree, where each leaf carries an action marker:
 * ```
 * - `/`
 *     - `src/`
 *         - `Main.kt` OPEN
 *         - `resources/` EXPAND
 * ```
 *
 * Directories with nested items are *dived into* (they are already expanded); an `EXPAND` leaf asks
 * to expand a still-collapsed directory, and an `OPEN` leaf asks to open a still-closed file.
 */
internal data object ScoutRequest_utils {
  private const val openMarker = "OPEN"

  private const val expandMarker = "EXPAND"

  fun ScoutRequest.dump(): MdElement =
      MdElement(blocks = rationale.blocks + requestedAdjustment.dumpAdjustmentList())

  fun ScoutRequest.Companion.load(
      element: MdElement,
  ): ScoutRequest {
    val adjustmentList =
        element.blocks.lastOrNull() as? MdBlock.ListBlock
            ?: error("Expected the scout request to end with an adjustment list")

    return ScoutRequest(
        rationale = MdElement(blocks = element.blocks.dropLast(1)),
        requestedAdjustment = adjustmentList.loadAdjustment(),
    )
  }

  fun ScoutRequest.SystemResponse.dump(): MdDocument =
      MdDocument(
          rootChapter =
              MdChapter.leaf(
                  title = MdInlineContent.of("APPLIED"),
                  element =
                      MdElement(
                          blocks =
                              listOf(
                                  MdBlock.Paragraph.of(
                                      "The requested adjustment has been applied (t=" +
                                          "${approvalTimestamp.t}). The newly opened files now " +
                                          "appear in the worktree shown above.",
                                  ),
                              ),
                      ),
              ),
      )

  private fun VedWorktreeAdjustment.dumpAdjustmentList(): MdBlock.ListBlock =
      MdBlock.ListBlock.of(
          items = listOf(rootDirectoryAdjustment.dumpDiveItem(directoryName = null)),
      )

  private fun VedDirectoryAdjustment.Dive.dumpDiveItem(
      directoryName: UfsName.Literal?,
  ): MdBlock.ListBlock.Item {
    val directoryNameText =
        when (directoryName) {
          null -> "/"
          else -> "${directoryName.content}/"
        }

    return MdBlock.ListBlock.Item.of(
        inlineNodes = listOf(MdInlineNode.Code(directoryNameText)),
        nestedItems =
            childAdjustmentByName.entries
                .sortedBy { (name, _) -> name.content }
                .map { (name, adjustment) -> adjustment.dumpEntityItem(entityName = name) },
    )
  }

  private fun VedEntityAdjustment.dumpEntityItem(
      entityName: UfsName.Literal,
  ): MdBlock.ListBlock.Item =
      when (this) {
        is VedDirectoryAdjustment.Dive -> dumpDiveItem(directoryName = entityName)

        VedDirectoryAdjustment.Expand ->
            MdBlock.ListBlock.Item.of(
                inlineNodes =
                    listOf(
                        MdInlineNode.Code("${entityName.content}/"),
                        MdInlineNode.Text(" $expandMarker"),
                    ),
            )

        VedFileAdjustment.Open ->
            MdBlock.ListBlock.Item.of(
                inlineNodes =
                    listOf(
                        MdInlineNode.Code(entityName.content),
                        MdInlineNode.Text(" $openMarker"),
                    ),
            )
      }

  private fun MdBlock.ListBlock.loadAdjustment(): VedWorktreeAdjustment {
    val rootItem =
        topLevel.items.singleOrNull()
            ?: error("Expected a single root `/` item in the adjustment list")

    return VedWorktreeAdjustment(rootDirectoryAdjustment = rootItem.loadDive())
  }

  private fun MdBlock.ListBlock.Item.loadDive(): VedDirectoryAdjustment.Dive {
    val nestedItems = nestedLevel?.items ?: emptyList()

    return VedDirectoryAdjustment.Dive(
        childAdjustmentByName = nestedItems.associate { item -> item.loadEntity() },
    )
  }

  private fun MdBlock.ListBlock.Item.loadEntity(): Pair<UfsName.Literal, VedEntityAdjustment> {
    val nodes = content.inlineNodes

    val rawName =
        (nodes.firstOrNull() as? MdInlineNode.Code)?.code
            ?: error("Expected an adjustment item to start with an inline-code name")

    val marker =
        when (nodes.size) {
          1 -> ""

          2 ->
              (nodes[1] as? MdInlineNode.Text)?.text?.trim()
                  ?: error("Expected a text marker after the name")

          else -> error("Unexpected adjustment item content with ${nodes.size} inline nodes")
        }

    return when (marker) {
      openMarker -> UfsName.Literal(rawName) to VedFileAdjustment.Open

      expandMarker -> UfsName.Literal(rawName.removeSuffix("/")) to VedDirectoryAdjustment.Expand

      "" -> UfsName.Literal(rawName.removeSuffix("/")) to loadDive()

      else -> error("Unrecognized adjustment marker: `$marker`")
    }
  }
}
