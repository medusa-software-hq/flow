package software.medusa.code_agent.structure

/**
 * A state of the code file structure, which can be used to track the presentation state of sections
 * in the code file.
 */
@JvmInline
value class CodeFileStructureState(
    val topLevelSectionStructureStateBySymbol:
        Map<CodeFileStructure.Symbol, CodeFileStructure.SectionStructure>,
) {
  /** The presentation state of a specific code section. */
  data class SectionStructureState(
      val expansion: Expansion,
      val nestedSectionStructureStateBySymbol:
          Map<CodeFileStructure.Symbol, CodeFileStructure.SectionStructure>,
  ) {
    enum class Expansion {
      Expanded,
      Collapsed,
    }
  }
}
