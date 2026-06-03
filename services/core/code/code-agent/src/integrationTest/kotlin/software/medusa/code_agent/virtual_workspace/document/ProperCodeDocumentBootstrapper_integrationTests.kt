package software.medusa.code_agent.virtual_workspace.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.markdown.MarkdownInline
import software.medusa.openai_client.OpenAiClient

class ProperCodeDocumentBootstrapper_integrationTests {
  companion object {
    private const val apiKeyEnvVarName = "OPENROUTER_API_KEY"

    private val apiKey = System.getenv(apiKeyEnvVarName)

    private val sampleFileCode =
        CodeBlock.of(
            /* 01 */ "package demo.analytics",
            /* 02 */ "",
            /* 03 */ "class SessionScorer(private val decayFactor: Double) {",
            /* 04 */ "  //#region Core session scoring pipeline",
            /* 05 */ "  fun score(events: List<Int>): Double {",
            /* 06 */ "    val normalized = normalize(events)",
            /* 07 */ "    return applyDecay(normalized)",
            /* 08 */ "  }",
            /* 09 */ "  //#endregion",
            /* 10 */ "",
            /* 11 */ "  //#region Historical score reconciliation",
            /* 12 */ "  fun reconcile(currentScore: Double, persistedScore: Double?): Double =",
            /* 13 */ "      when (persistedScore) {",
            /* 14 */ "        null -> currentScore",
            /* 15 */ "        else -> (currentScore + persistedScore) / 2.0",
            /* 16 */ "      }",
            /* 17 */ "  //#endregion",
            /* 18 */ "",
            /* 19 */ "  //#region Rare admin-only tuning helpers",
            /* 20 */ "  fun dumpCalibrationVector(): List<Double> = listOf(0.1, decayFactor, 0.9)",
            /* 21 */ "",
            /* 22 */ "  fun previewCalibrationStep(step: Int): Double = step * decayFactor",
            /* 23 */ "  //#endregion",
            /* 24 */ "",
            /* 25 */ "  private fun normalize(events: List<Int>): Double =",
            /* 26 */ "      events.sum().toDouble() / events.size.coerceAtLeast(1)",
            /* 27 */ "",
            /* 28 */ "  private fun applyDecay(value: Double): Double = value * decayFactor",
            /* 29 */ "}",
        )

    private fun buildClient(): OpenAiClient {
      assumeTrue(apiKey != null, "Environment variable $apiKeyEnvVarName is not set")

      return OpenAiClient.build(
          config =
              OpenAiClient.Config(
                  baseUrl = OpenAiClient.openRouterBaseUrl,
                  apiKey = checkNotNull(apiKey),
              ),
      )
    }
  }

  @Test
  fun test_bootstrapDocument() = runTest {
    val openAiClient = buildClient()

    val bootstrapper =
        ProperCodeDocumentBootstrapper(
            openAiClient = openAiClient,
        )

    val inputDocument =
        CodeDocument.load(
            language = CodeLanguage.Kotlin,
            content = TechFileContent.Code(code = sampleFileCode),
        )

    val bootstrappedDocument = bootstrapper.bootstrapDocument(document = inputDocument)

    val topLevelRegions =
        bootstrappedDocument.rootContainer.nodes.filterIsInstance<CodeNode.Region>()
    val regionsByTitle = topLevelRegions.associateBy { it.title.text.text }

    val corePipelineRegion = regionsByTitle["Core session scoring pipeline"]
    val reconciliationRegion = regionsByTitle["Historical score reconciliation"]
    val adminHelpersRegion = regionsByTitle["Rare admin-only tuning helpers"]

    assertNotNull(corePipelineRegion, "Expected core pipeline region")
    assertNotNull(reconciliationRegion, "Expected reconciliation region")
    assertNotNull(adminHelpersRegion, "Expected admin helpers region")

    assertEquals(CodeNode.Region.State.Expanded, corePipelineRegion.state)
    assertEquals(CodeNode.Region.State.Collapsed, adminHelpersRegion.state)

    assertSummaryIsNonEmpty(corePipelineRegion)
    assertSummaryIsNonEmpty(reconciliationRegion)
    assertSummaryIsNonEmpty(adminHelpersRegion)
  }

  private fun assertSummaryIsNonEmpty(region: CodeNode.Region) {
    val summary =
        requireNotNull(region.summary) { "Expected summary for region '${region.title.text.text}'" }

    assertTrue(
        summary.paragraph.inlineContent
            .joinToString(separator = "") { inline -> inline.toPlainText() }
            .isNotBlank(),
        "Expected summary to be non-blank for region '${region.title.text.text}'",
    )
  }

  private fun MarkdownInline.toPlainText(): String =
      when (this) {
        is MarkdownInline.Text -> text
        is MarkdownInline.Code -> code
        is MarkdownInline.Emphasis -> content.joinToString(separator = "") { it.toPlainText() }
        is MarkdownInline.Strong -> content.joinToString(separator = "") { it.toPlainText() }
        is MarkdownInline.Link -> content.joinToString(separator = "") { it.toPlainText() }
        MarkdownInline.SoftBreak -> "\n"
        MarkdownInline.HardBreak -> "\n"
      }
}
