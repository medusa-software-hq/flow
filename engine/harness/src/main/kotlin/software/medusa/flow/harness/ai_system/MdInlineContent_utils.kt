package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.markdown.MdInlineNode

internal data object MdInlineContent_utils {
  /**
   * Returns the text of this inline content, requiring it to be a single literal text node — the
   * shape of the ad-hoc format's plain headings (e.g. `STOP`, `UPDATE 11-22`).
   */
  fun MdInlineContent.extractText(): String {
    val node =
        inlineNodes.singleOrNull()
            ?: error("Expected a single text node, got ${inlineNodes.size} nodes")

    return (node as? MdInlineNode.Text)?.text
        ?: error("Expected a text node, got ${node::class.simpleName}")
  }

  /**
   * Returns the literal of this inline content, requiring it to be a single inline-code node — the
   * shape of the ad-hoc format's code headings (e.g. a `` `/src/Main.kt` `` file path).
   */
  fun MdInlineContent.extractInlineCode(): String {
    val node =
        inlineNodes.singleOrNull()
            ?: error("Expected a single inline-code node, got ${inlineNodes.size} nodes")

    return (node as? MdInlineNode.Code)?.code
        ?: error("Expected an inline-code node, got ${node::class.simpleName}")
  }
}
