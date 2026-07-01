package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.markdown.MdInlineNode
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsLiteralRelativePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.commons.unix.path.UfsRelativePath
import software.medusa.commons.unix.path.UfsRelativePath.Companion.extend
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutCommand
import software.medusa.flow.harness.ai_system.MdInlineContent_utils.extractText
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedEntityAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment

internal data object ScoutCommand_utils {
  internal const val exploreKeyword = "EXPLORE"

  internal const val readyKeyword = "READY"

  internal const val openMarker = "OPEN"

  internal const val expandMarker = "EXPAND"

  private val stopChapter =
      MdChapter.leaf(
          title = MdInlineContent.of(readyKeyword),
          element = MdElement.Empty,
      )

  fun ScoutCommand.Companion.load(
      document: MdDocument,
  ): ScoutCommand {
    return when (val heading = document.rootChapter.title.extractText()) {
      exploreKeyword ->
          ScoutCommand.Continue.load(
              element = document.rootChapter.element,
          )

      readyKeyword -> ScoutCommand.Stop

      else -> error("Unrecognized scouting-result heading: `$heading`")
    }
  }

  fun ScoutCommand.Continue.Companion.load(
      element: MdElement,
  ): ScoutCommand {
    val listBlock =
        element.blocks.lastOrNull() as? MdBlock.ListBlock
            ?: error("Expected the scout request to end with an adjustment list")

    return ScoutCommand.Continue(
        rationale = MdElement(blocks = element.blocks.dropLast(1)),
        requestedAdjustment =
            loadWorktreeAdjustment(
                listBlock = listBlock,
            ),
    )
  }

  private fun loadWorktreeAdjustment(
      listBlock: MdBlock.ListBlock,
  ): VedWorktreeAdjustment =
      VedWorktreeAdjustment(
          rootDirectoryAdjustment =
              buildDiveAdjustment(leafEntries = flattenLeafEntries(listLevel = listBlock.topLevel)),
      )

  /**
   * Flattens an adjustment list into one entry per leaf — an [VedFileAdjustment.Open] or
   * [VedDirectoryAdjustment.Expand] — each keyed by its full path. The deep-path form
   * (`/app/src/Main.kt OPEN`) and the nested-list form both reduce to the same flat leaves, so the
   * tree can be rebuilt by merging shared prefixes.
   */
  private fun flattenLeafEntries(
      listLevel: MdBlock.ListBlock.Level,
  ): List<Pair<List<UfsName.Literal>, VedEntityAdjustment>> =
      listLevel.items.flatMap { listItem -> flattenLeafEntries(listItem = listItem) }

  private fun flattenLeafEntries(
      listItem: MdBlock.ListBlock.Item,
  ): List<Pair<List<UfsName.Literal>, VedEntityAdjustment>> {
    val nodes = listItem.content.inlineNodes

    val rawPath =
        (nodes.firstOrNull() as? MdInlineNode.Code)?.code
            ?: error("Expected an adjustment item to start with an inline-code path")

    val marker: String? =
        when (nodes.size) {
          1 -> null

          2 ->
              (nodes[1] as? MdInlineNode.Text)?.text?.trim()
                  ?: error("Expected a text marker after the path")

          else -> error("Unexpected adjustment item content with ${nodes.size} inline nodes")
        }

    val prefixNames = parseAbsolutePathNames(rawPath = rawPath.removeSuffix("/"))

    return when (marker) {
      openMarker -> listOf(prefixNames to VedFileAdjustment.Open)

      expandMarker -> listOf(prefixNames to VedDirectoryAdjustment.Expand)

      null -> {
        val nestedLevel =
            listItem.nestedLevel ?: error("A list item without a marker must have a nested level")

        flattenLeafEntries(listLevel = nestedLevel).map { (suffixNames, leafAdjustment) ->
          (prefixNames + suffixNames) to leafAdjustment
        }
      }

      else -> error("Unrecognized adjustment marker: `$marker`")
    }
  }

  private fun parseAbsolutePathNames(
      rawPath: String,
  ): List<UfsName.Literal> {
    val absolutePath =
        UfsAbsolutePath.parse(rawPath).toLiteral()
            ?: error("Adjustment item path is not a literal absolute path: `$rawPath`")

    val names = absolutePath.innerPath.names

    require(names.isNotEmpty()) { "Adjustment item path does not point to an entity: `$rawPath`" }

    return names
  }

  /**
   * Rebuilds the dive tree from flat leaf entries, merging entries that share a common path prefix
   * — so a flat list like `/app/build.gradle.kts` and `/app/src/Main.kt` joins under a single
   * `/app` dive instead of the latter clobbering the former.
   */
  private fun buildDiveAdjustment(
      leafEntries: List<Pair<List<UfsName.Literal>, VedEntityAdjustment>>,
  ): VedDirectoryAdjustment.Dive {
    val childAdjustmentByName: Map<UfsName.Literal, VedEntityAdjustment> =
        leafEntries
            .groupBy { (names, _) -> names.first() }
            .mapValues { (_, entriesForName) ->
              val tailEntries = entriesForName.map { (names, leafAdjustment) ->
                names.drop(1) to leafAdjustment
              }

              val leafEntry = tailEntries.firstOrNull { (names, _) -> names.isEmpty() }

              when {
                leafEntry != null -> leafEntry.second

                else -> buildDiveAdjustment(leafEntries = tailEntries)
              }
            }

    return VedDirectoryAdjustment.Dive(childAdjustmentByName = childAdjustmentByName)
  }

  fun ScoutCommand.dump(): MdChapter =
      when (this) {
        is ScoutCommand.Continue ->
            MdChapter.leaf(
                title = MdInlineContent.of(exploreKeyword),
                element = dumpContinue(continueCommand = this),
            )

        ScoutCommand.Stop -> stopChapter
      }

  private fun dumpContinue(
      continueCommand: ScoutCommand.Continue,
  ): MdElement =
      MdElement(
          blocks =
              continueCommand.rationale.blocks +
                  dumpWorktreeAdjustment(
                      worktreeAdjustment = continueCommand.requestedAdjustment,
                  ),
      )

  private fun dumpWorktreeAdjustment(
      worktreeAdjustment: VedWorktreeAdjustment,
  ): MdBlock.ListBlock =
      MdBlock.ListBlock(
          topLevel =
              dumpTopLevelDiveAdjustment(
                  diveAdjustment = worktreeAdjustment.rootDirectoryAdjustment,
              ),
      )

  private fun renderAdjustmentNode(
      prefixPath: UfsLiteralRelativePath,
      marker: String?,
      nestedLevel: MdBlock.ListBlock.Level?,
  ): MdBlock.ListBlock.Item =
      MdBlock.ListBlock.Item(
          content =
              MdInlineContent(
                  inlineNodes =
                      listOfNotNull(
                          MdInlineNode.Code("/" + prefixPath.toUnixRelativePathString()),
                          marker?.let { MdInlineNode.Text(" $it") },
                      ),
              ),
          nestedLevel = nestedLevel,
      )

  private fun dumpEntityAdjustment(
      prefixPath: UfsLiteralRelativePath,
      entityAdjustment: VedEntityAdjustment,
  ): MdBlock.ListBlock.Item =
      when (entityAdjustment) {
        VedFileAdjustment.Open ->
            renderAdjustmentNode(
                prefixPath = prefixPath,
                marker = openMarker,
                nestedLevel = null,
            )

        VedDirectoryAdjustment.Expand ->
            renderAdjustmentNode(
                prefixPath = prefixPath,
                marker = expandMarker,
                nestedLevel = null,
            )

        is VedDirectoryAdjustment.Dive -> {
          dumpNestedDiveAdjustment(
              prefixPath = prefixPath,
              diveAdjustment = entityAdjustment,
          )
        }
      }

  private fun dumpNestedDiveAdjustment(
      prefixPath: UfsLiteralRelativePath,
      diveAdjustment: VedDirectoryAdjustment.Dive,
  ): MdBlock.ListBlock.Item =
      when {
        diveAdjustment.childAdjustmentByName.size == 1 -> {
          val (childName, childAdjustment) = diveAdjustment.childAdjustmentByName.entries.single()

          val extendedPrefixPath = prefixPath.extend(name = childName)

          dumpEntityAdjustment(
              prefixPath = extendedPrefixPath,
              entityAdjustment = childAdjustment,
          )
        }

        else ->
            renderAdjustmentNode(
                prefixPath = prefixPath,
                marker = null,
                nestedLevel =
                    dumpTopLevelDiveAdjustment(
                        diveAdjustment = diveAdjustment,
                    ),
            )
      }

  private fun dumpTopLevelDiveAdjustment(
      diveAdjustment: VedDirectoryAdjustment.Dive,
  ): MdBlock.ListBlock.Level =
      MdBlock.ListBlock.Level(
          items =
              diveAdjustment.childAdjustmentByName.entries
                  .sortedBy { (name, _) -> name.content }
                  .map { (name, adjustment) ->
                    dumpEntityAdjustment(
                        prefixPath = UfsRelativePath.of(name),
                        entityAdjustment = adjustment,
                    )
                  },
      )

  fun ScoutCommand.SystemResponse.dump(): MdChapter =
      MdChapter.leaf(
          title = MdInlineContent.of("Worktree adjusted"),
          element =
              MdElement(
                  blocks =
                      listOf(
                          MdBlock.Paragraph.of(
                              "The worktree was adjusted according to your request."
                          ),
                          MdBlock.Paragraph.of("Timestamp: t = ${approvalTimestamp.t}"),
                          MdBlock.Paragraph.of(
                              inlineNodes =
                                  listOf(
                                      MdInlineNode.Strong.of("NOTE:"),
                                      MdInlineNode.Text(" The freshly opened files are visible "),
                                      MdInlineNode.Emphasis.of("above"),
                                      MdInlineNode.Text("this message, in the Worktree section."),
                                  ),
                          ),
                          MdBlock.Paragraph.of(
                              "If the freshly opened files revealed new information, you can continue exploring ($exploreKeyword). Otherwise, declare readiness ($readyKeyword)."
                          ),
                          MdBlock.Paragraph.of("Respond in the Scout Response Format."),
                      ),
              ),
      )
}
