package software.medusa.code_agent.structure

import software.medusa.commons.code.CodeBlock
import software.medusa.markdown.MarkdownBlock

/** A simplified syntactic structure of a code file. */
data class CodeFileStructure(
    /** A map of top-level sections in the code file, keyed by a symbol representing the section. */
    val topLevelSectionStructureBySymbol: Map<Symbol, SectionStructure>,
) {
  sealed interface Symbol {
    companion object
  }

  data object ImportBlockSymbol : Symbol

  @JvmInline value class EntityNameSymbol(val name: String) : Symbol

  data class SectionStructure(
      val coveredRange: CodeBlock.LineIndexRange,
      val sectionSummary: MarkdownBlock.Paragraph,
      val nestedSectionStructureBySymbol: Map<Symbol, SectionStructure>,
  ) {
    companion object;

    init {
      val isNonOverlapping =
          nestedSectionStructureBySymbol.values
              .sortedBy { structure -> structure.coveredRange.startIndex }
              .zipWithNext()
              .none { (prevStructure, nextStructure) ->
                prevStructure.coveredRange.collides(nextStructure.coveredRange)
              }

      require(isNonOverlapping) { "Group sections cannot have overlapping covered ranges" }
    }
  }

  companion object
}
