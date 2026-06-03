package software.medusa.code_agent.virtual_workspace.document

interface CodeDocumentBootstrapper {
  /**
   * A document is considered _bootstrapped_ when:
   *
   * - Likely relevant regions are expanded
   * - Likely irrelevant regions are collapsed
   * - All regions have the summaries filled
   *
   * @return A bootstrapped form of [document].
   */
  suspend fun bootstrapDocument(
      document: CodeDocument,
  ): CodeDocument
}
