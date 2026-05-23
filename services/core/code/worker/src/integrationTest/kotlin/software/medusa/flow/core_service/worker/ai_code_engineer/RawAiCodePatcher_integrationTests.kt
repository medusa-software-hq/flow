package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.yaml.snakeyaml.Yaml
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.flow.core_service.worker.code.applyChange
import software.medusa.openai_client.OpenAiClient

class RawAiCodePatcher_integrationTests {
  companion object {
    private const val apiKeyEnvVarName = "OPENAI_API_KEY"

    private val apiKey = System.getenv(apiKeyEnvVarName)

    private fun buildClient(): OpenAiClient {
      assumeTrue(apiKey != null, "Environment variable $apiKeyEnvVarName is not set")
      val actualApiKey = checkNotNull(apiKey)

      return OpenAiClient.build(
          config =
              OpenAiClient.Config(
                  baseUrl = OpenAiClient.openAiBaseUrl,
                  apiKey = actualApiKey,
              ),
      )
    }

    private val yaml = Yaml()

    private val moduleYamlFilePath =
        RelativeUnixPath.of(
            UnixPath.Name.Literal("module.yaml"),
        )

    private val moduleYamlContent =
        TechFileContent.Code.parse(
            """
            submodules:
              backend: backend
              frontend: frontend
            facets:
              formatting:
                formatting:
                  fix:
                    gradleTask: spotlessApply
                verification:
                  - name: formatting-check
                    run:
                      gradleTask: spotlessCheck
              backend:
                formatting:
                  fix:
                    gradleTask: :backend:fmt
                verification:
                  - name: unit-tests
                    run:
                      gradleTask: :backend:test
                  - name: detekt
                    run:
                      gradleTask: :backend:detekt
              frontend:
                formatting:
                  fix:
                    npxArgs: [prettier, --write, src]
                verification:
                  - name: typecheck
                    run:
                      npxArgs: [tsc, --noEmit]
                  - name: lint
                    run:
                      npxArgs: [eslint, src]
            """
                .trimIndent(),
        )

    private val maskedCodeCatalog =
        MaskedCodeCatalog(
            maskedCodeFileContentByPath =
                mapOf(
                    moduleYamlFilePath to
                        MaskedCodeFileContent(
                            codeFileContent = moduleYamlContent,
                            mask = MaskedCodeFileContent.Mask.Empty,
                        ),
                ),
        )
  }

  @Test
  fun test_patchToCompleteTask_updatesYamlConfigStructure() = runTest {
    val patcher = RawAiCodePatcher(openAiClient = buildClient())

    val patchSet =
        patcher
            .patchToCompleteTask(
                taskDescription =
                    """
                    Update module.yaml.
                    Remove the frontend submodule entry.
                    Remove the backend 'detekt' verification entry.
                    Change the top-level formatting gradle task from spotlessApply to formatApply.
                    Change the backend formatting gradle task from :backend:fmt to :backend:format.
                    Add a backend verification entry named integration-tests running gradle task :backend:integrationTest.
                    Add a deployment facet with one verification entry named deploy-check running gradle task :backend:deployCheck.
                    Preserve the rest of the YAML structure.
                    """
                        .trimIndent(),
            )
            .generateChanges(
                maskedCodeCatalog = maskedCodeCatalog,
            )

    val moduleYamlPatch =
        assertNotNull(
            patchSet.changeByFilePath[moduleYamlFilePath] as? Change.Patch,
        )

    val patchedModuleYamlText = moduleYamlContent.applyChange(moduleYamlPatch).dump()

    val parsedYaml =
        checkNotNull(
            yaml.load<Map<String, Any?>>(patchedModuleYamlText),
        )

    val submodules = checkNotNull(parsedYaml["submodules"] as? Map<*, *>)
    val facets = checkNotNull(parsedYaml["facets"] as? Map<*, *>)
    val formattingFacet = checkNotNull(facets["formatting"] as? Map<*, *>)
    val formattingConfig = checkNotNull(formattingFacet["formatting"] as? Map<*, *>)
    val formattingFix = checkNotNull(formattingConfig["fix"] as? Map<*, *>)
    val backendFacet = checkNotNull(facets["backend"] as? Map<*, *>)
    val backendFormatting = checkNotNull(backendFacet["formatting"] as? Map<*, *>)
    val backendFix = checkNotNull(backendFormatting["fix"] as? Map<*, *>)
    val backendVerification = checkNotNull(backendFacet["verification"] as? List<*>)
    val deploymentFacet = checkNotNull(facets["deployment"] as? Map<*, *>)
    val deploymentVerification = checkNotNull(deploymentFacet["verification"] as? List<*>)

    assertEquals(
        expected = setOf("backend"),
        actual = submodules.keys.map { it.toString() }.toSet(),
    )
    assertEquals(
        expected = "formatApply",
        actual = formattingFix["gradleTask"],
    )
    assertEquals(
        expected = ":backend:format",
        actual = backendFix["gradleTask"],
    )
    assertEquals(
        expected = setOf("unit-tests", "integration-tests"),
        actual =
            backendVerification
                .map { checkNotNull(it as? Map<*, *>) }
                .map { verification -> verification["name"].toString() }
                .toSet(),
    )
    assertTrue(
        actual =
            backendVerification
                .map { checkNotNull(it as? Map<*, *>) }
                .any { verification ->
                  verification["name"] == "integration-tests" &&
                      (checkNotNull(verification["run"] as? Map<*, *>))["gradleTask"] ==
                          ":backend:integrationTest"
                },
    )
    assertEquals(
        expected = 1,
        actual = deploymentVerification.size,
    )
    assertTrue(
        actual =
            deploymentVerification
                .map { checkNotNull(it as? Map<*, *>) }
                .any { verification ->
                  verification["name"] == "deploy-check" &&
                      (checkNotNull(verification["run"] as? Map<*, *>))["gradleTask"] ==
                          ":backend:deployCheck"
                },
    )
  }
}
