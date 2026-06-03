package software.medusa.code_agent.virtual_workspace.document

import kotlinx.serialization.Serializable
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownInline
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiMessage
import software.medusa.openai_client.OpenAiModel
import software.medusa.openai_client.OpenAiRole
import software.medusa.openai_client.createStructuredCompletion

class ProperCodeDocumentBootstrapper(
    private val openAiClient: OpenAiClient,
) : CodeDocumentBootstrapper {
  /**
   * A document is considered _bootstrapped_ when:
   *
   * - Likely relevant regions are expanded
   * - Likely irrelevant regions are collapsed
   * - All regions have the summaries filled
   *
   * @return A bootstrapped form of [document].
   */
  override suspend fun bootstrapDocument(
      document: CodeDocument,
  ): CodeDocument {
    val regionPaths = document.rootContainer.collectRegionPaths()

    if (regionPaths.isEmpty()) {
      return document
    }

    val fileContent =
        with(CodeDocument.DumpContentContext(language = document.language)) {
          document.dumpContent()
        }

    val bootstrapOutput =
        openAiClient
            .createStructuredCompletion(
                request =
                    OpenAiClient.CompletionRequest(
                        input =
                            buildInputChat(fileContent = fileContent, regionPaths = regionPaths),
                        model = bootstrapModel,
                    ),
                responseSchemaName = "code_document_bootstrap",
                responseSerializer = BootstrapResponse.serializer(),
            )
            .responseObject

    val decisionsByPath = bootstrapOutput.regions.associateBy { it.path }

    require(decisionsByPath.keys == regionPaths.toSet()) {
      "Bootstrapper must return exactly one decision for each region path"
    }

    return document.copy(
        rootContainer = document.rootContainer.withBootstrapData(decisionsByPath = decisionsByPath),
    )
  }

  companion object {
    @Serializable
    data class BootstrapResponse(
        val regions: List<RegionDecision>,
    ) {
      @Serializable
      data class RegionDecision(
          val path: String,
          val summary: String,
          val shouldExpand: Boolean,
      )
    }

    private val bootstrapModel = OpenAiModel.Gemma12B

    private val instructionPrompt =
        CodeBlock.of(
            "You are bootstrapping a code document built from explicit user-authored regions.",
            "",
            "Important model of the problem:",
            "- The file already contains the only valid regions.",
            "- Do not invent, merge, split, rename, or reinterpret regions.",
            "- Your job is only to decide which existing regions should be expanded by default and to summarize each region.",
            "",
            "Expansion goal:",
            "- Expand regions that are likely central for understanding the file quickly.",
            "- Collapse regions that are likely secondary, incidental, boilerplate, low-signal, or rarely needed on a first read.",
            "- Prefer expanding the main implementation path, the primary type or entrypoint, and regions whose title strongly matches the file's main concept.",
            "- Prefer collapsing helpers, extensions, utilities, compatibility shims, testsupport, debug code, migration leftovers, and rarely used paths.",
            "- If unsure, lean slightly toward expanding only the most relevant regions rather than everything.",
            "",
            "Summary goal:",
            "- Each region needs a concise summary based on its actual contents.",
            "- Each summary must be exactly one line of plain text.",
            "- Do not use bullets, markdown formatting, backticks, or line breaks in summaries.",
            "- Summaries should say what the region is for, not just restate the title.",
            "",
            "Path format:",
            "- Each region is identified by a unique path like `Outer / Inner / More specific region`.",
            "- Return the exact path strings provided by the user input.",
            "",
            "Output requirements:",
            "- Return one item for every provided region path.",
            "- Do not omit any region.",
            "- Do not add extra regions.",
        )

    private fun buildInputChat(
        fileContent: TechFileContent.Code,
        regionPaths: List<String>,
    ): OpenAiChat =
        OpenAiChat(
            messages =
                listOf(
                    OpenAiMessage(
                        role = OpenAiRole.System,
                        text = instructionPrompt.dump(),
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.User,
                        text =
                            buildString {
                              appendLine("File content:")
                              appendLine("```")
                              append(fileContent.dump())
                              appendLine("```")
                              appendLine()
                              appendLine("Region paths:")
                              regionPaths.forEach { path -> appendLine("- $path") }
                            },
                    ),
                ),
        )

    private fun CodeNode.Container.collectRegionPaths(
        parentPath: List<String> = emptyList(),
    ): List<String> = nodes.flatMap { node ->
      when (node) {
        is CodeNode.Plain -> emptyList()
        is CodeNode.Region -> {
          val pathSegments = parentPath + node.title.text.text
          val path = pathSegments.joinToString(separator = " / ")

          listOf(path) + node.innerContainer.collectRegionPaths(parentPath = pathSegments)
        }
      }
    }

    private fun CodeNode.Container.withBootstrapData(
        parentPath: List<String> = emptyList(),
        decisionsByPath: Map<String, BootstrapResponse.RegionDecision>,
    ): CodeNode.Container =
        CodeNode.Container(
            nodes =
                nodes.map { node ->
                  when (node) {
                    is CodeNode.Plain -> node
                    is CodeNode.Region -> {
                      val pathSegments = parentPath + node.title.text.text
                      val path = pathSegments.joinToString(separator = " / ")
                      val decision =
                          decisionsByPath[path]
                              ?: throw IllegalStateException(
                                  "Missing bootstrap decision for region '$path'"
                              )

                      node.copy(
                          summary =
                              CodeNode.Region.Summary(
                                  paragraph =
                                      MarkdownBlock.Paragraph(
                                          inlineContent =
                                              listOf(MarkdownInline.Text(decision.summary)),
                                      ),
                              ),
                          state =
                              when (decision.shouldExpand) {
                                true -> CodeNode.Region.State.Expanded
                                false -> CodeNode.Region.State.Collapsed
                              },
                          innerContainer =
                              node.innerContainer.withBootstrapData(
                                  parentPath = pathSegments,
                                  decisionsByPath = decisionsByPath,
                              ),
                      )
                    }
                  }
                },
        )
  }
}
