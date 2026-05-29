package software.medusa.code_agent.exploration

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.code_agent.structure.CodeFileStructure
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.commons.paths.UnixPath
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownInline
import software.medusa.openai_client.OpenAiClient

class ProperCodeFileExplorer_integrationTests {
  companion object {
    private const val apiKeyEnvVarName = "OPENROUTER_API_KEY"

    private val apiKey = System.getenv(apiKeyEnvVarName)

    @Suppress("CanConvertToMultiDollarString")
    private val sampleFileCode =
        CodeBlock.of(
            /* 01 */ "package demo.billing",
            /* 02 */ "",
            /* 03 */ "import kotlin.math.max",
            /* 04 */ "",
            /* 05 */ "data class InvoiceLine(",
            /* 06 */ "    val name: String,",
            /* 07 */ "    val unitPriceCents: Int,",
            /* 08 */ "    val quantity: Int,",
            /* 09 */ ")",
            /* 10 */ "",
            /* 11 */ "class InvoiceCalculator(",
            /* 12 */ "    private val taxRatePercent: Int,",
            /* 13 */ ") {",
            /* 14 */ "    fun subtotalCents(lines: List<InvoiceLine>): Int =",
            /* 15 */ "        lines.sumOf { line -> line.unitPriceCents * line.quantity }",
            /* 16 */ "",
            /* 17 */ "    fun discountCents(subtotalCents: Int, customerTier: String): Int =",
            /* 18 */ "        when (customerTier.lowercase()) {",
            /* 19 */ "            \"gold\" -> subtotalCents / 10",
            /* 20 */ "            \"silver\" -> subtotalCents / 20",
            /* 21 */ "            else -> 0",
            /* 22 */ "        }",
            /* 23 */ "",
            /* 24 */ "    fun totalCents(lines: List<InvoiceLine>, customerTier: String): Int {",
            /* 25 */ "        val subtotal = subtotalCents(lines)",
            /* 26 */ "        val discount = discountCents(subtotal, customerTier)",
            /* 27 */ "        val taxedBase = max(subtotal - discount, 0)",
            /* 28 */ "        val tax = taxedBase * taxRatePercent / 100",
            /* 29 */ "        return taxedBase + tax",
            /* 30 */ "    }",
            /* 31 */ "}",
            /* 32 */ "",
            /* 33 */ "fun InvoiceLine.describe(): String = \"\$quantity x \$name @ \${unitPriceCents / 100.0}\"",
        )

    private fun buildClient(): OpenAiClient {
      assumeTrue(apiKey != null, "Environment variable $apiKeyEnvVarName is not set")

      val actualApiKey = checkNotNull(apiKey)

      return OpenAiClient.build(
          config =
              OpenAiClient.Config(
                  baseUrl = OpenAiClient.openRouterBaseUrl,
                  apiKey = actualApiKey,
              ),
      )
    }
  }

  @Test
  fun test_exploreFile() = runTest {
    val openAiClient = buildClient()

    val structureExtractor =
        ProperCodeFileExplorer(
            openAiClient = openAiClient,
        )

    val explorationResult =
        structureExtractor.exploreFile(
            fileName = UnixPath.Name.Literal("InvoiceCalculator.kt"),
            fileContent = TechFileContent.Code(code = sampleFileCode),
        )

    assertSummaryIsNonEmpty(explorationResult.fileSummary)

    val topLevelSections = explorationResult.fileStructure.topLevelSectionStructureBySymbol
    val invoiceCalculatorSection =
        topLevelSections[CodeFileStructure.EntityNameSymbol("InvoiceCalculator")]
    val describeSection = topLevelSections[CodeFileStructure.EntityNameSymbol("describe")]

    assertTrue(invoiceCalculatorSection != null, "Expected InvoiceCalculator top-level section")
    assertTrue(describeSection != null, "Expected describe top-level section")

    assertRangeApproximatelyEquals(
        expectedStartOneBased = 11,
        expectedEndInclusiveOneBased = 30,
        actualRange = requireNotNull(invoiceCalculatorSection).coveredRange,
    )
    assertSummaryIsNonEmpty(invoiceCalculatorSection.sectionSummary)

    val nestedSections = invoiceCalculatorSection.nestedSectionStructureBySymbol
    val subtotalSection = nestedSections[CodeFileStructure.EntityNameSymbol("subtotalCents")]
    val discountSection = nestedSections[CodeFileStructure.EntityNameSymbol("discountCents")]
    val totalSection = nestedSections[CodeFileStructure.EntityNameSymbol("totalCents")]

    assertTrue(subtotalSection != null, "Expected subtotalCents nested section")
    assertTrue(discountSection != null, "Expected discountCents nested section")
    assertTrue(totalSection != null, "Expected totalCents nested section")

    assertRangeApproximatelyEquals(
        expectedStartOneBased = 14,
        expectedEndInclusiveOneBased = 15,
        actualRange = requireNotNull(subtotalSection).coveredRange,
    )
    assertRangeApproximatelyEquals(
        expectedStartOneBased = 17,
        expectedEndInclusiveOneBased = 22,
        actualRange = requireNotNull(discountSection).coveredRange,
    )
    assertRangeApproximatelyEquals(
        expectedStartOneBased = 24,
        expectedEndInclusiveOneBased = 30,
        actualRange = requireNotNull(totalSection).coveredRange,
    )

    assertSummaryIsNonEmpty(subtotalSection.sectionSummary)
    assertSummaryIsNonEmpty(discountSection.sectionSummary)
    assertSummaryIsNonEmpty(totalSection.sectionSummary)

    assertRangeApproximatelyEquals(
        expectedStartOneBased = 33,
        expectedEndInclusiveOneBased = 33,
        actualRange = requireNotNull(describeSection).coveredRange,
    )
    assertSummaryIsNonEmpty(describeSection.sectionSummary)
  }

  private fun assertSummaryIsNonEmpty(summary: MarkdownBlock.Paragraph) {
    assertTrue(extractPlainText(summary).isNotBlank(), "Expected summary to be non-empty")
  }

  private fun assertRangeApproximatelyEquals(
      expectedStartOneBased: Int,
      expectedEndInclusiveOneBased: Int,
      actualRange: CodeBlock.LineIndexRange,
  ) {
    val actualStartOneBased = actualRange.startIndex.indexOneBased
    val actualEndInclusiveOneBased = actualRange.endIndexExclusive.indexOneBased - 1

    assertTrue(
        actualStartOneBased in (expectedStartOneBased - 1)..(expectedStartOneBased + 1),
        "Expected start line around $expectedStartOneBased, got $actualStartOneBased",
    )
    assertTrue(
        actualEndInclusiveOneBased in
            (expectedEndInclusiveOneBased - 1)..(expectedEndInclusiveOneBased + 1),
        "Expected end line around $expectedEndInclusiveOneBased, got $actualEndInclusiveOneBased",
    )
  }

  private fun extractPlainText(paragraph: MarkdownBlock.Paragraph): String =
      paragraph.inlineContent.joinToString(separator = "") { it.toPlainText() }

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
