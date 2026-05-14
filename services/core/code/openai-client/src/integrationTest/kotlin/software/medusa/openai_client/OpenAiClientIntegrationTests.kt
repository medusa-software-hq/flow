package software.medusa.openai_client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

class OpenAiClientIntegrationTests {
  @Serializable
  @Schema
  private data class CountryInfo(
      @Description("Name of the capital city of the country") val capital: String,
      @Description("Approximate population of the country") val population: Int,
  )

  companion object {
    private const val apiKeyEnvVarName = "OPENAI_API_KEY"

    private val apiKey =
        System.getenv(apiKeyEnvVarName)
            ?: error("Environment variable $apiKeyEnvVarName is not set")

    private fun buildClient() =
        OpenAiClient.build(
            config =
                OpenAiClient.Config(
                    baseUrl = OpenAiClient.openAiBaseUrl,
                    apiKey = apiKey,
                ),
        )
  }

  @Test
  fun testCreateUnstructuredCompletion() = runTest {
    val client = buildClient()

    val response =
        client.createUnstructuredCompletion(
            request =
                OpenAiClient.CompletionRequest(
                    input =
                        OpenAiCompletionInput(
                            messages =
                                listOf(
                                    OpenAiMessage(
                                        role = OpenAiRole.System,
                                        text = "You are a Star Wars meme expert.",
                                    ),
                                    OpenAiMessage(
                                        role = OpenAiRole.User,
                                        text = "Hello there!",
                                    ),
                                ),
                        ),
                    model = OpenAiModel.GptMidi,
                ),
        )

    assertEquals(
        actual = "General Kenobi!",
        expected = response.responseText,
    )
  }

  @Test
  fun testCreateStructuredCompletion() = runTest {
    val client = buildClient()

    val response =
        client.createStructuredCompletion(
            request =
                OpenAiClient.CompletionRequest(
                    input =
                        OpenAiCompletionInput(
                            messages =
                                listOf(
                                    OpenAiMessage(
                                        role = OpenAiRole.User,
                                        text = "Provide information about France",
                                    ),
                                ),
                        ),
                    model = OpenAiModel.GptMidi,
                ),
            responseSerializer = CountryInfo.serializer(),
        )

    val responseObject = response.responseObject

    assertEquals(
        expected = "Paris",
        actual = responseObject.capital,
    )

    assertTrue(
        responseObject.population in 65_000_000..70_000_000,
    )
  }
}
