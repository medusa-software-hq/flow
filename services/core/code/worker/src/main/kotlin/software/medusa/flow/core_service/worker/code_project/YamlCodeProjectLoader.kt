package software.medusa.flow.core_service.worker.code_project

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.yaml.snakeyaml.Yaml
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.filesystem.compat.readText
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.paths.resolve
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool
import software.medusa.flow.core_service.worker.code_project.tools.GradleOutputParser
import software.medusa.flow.core_service.worker.code_project.tools.GradleTool
import software.medusa.flow.core_service.worker.code_project.tools.NpxOutputParser
import software.medusa.flow.core_service.worker.code_project.tools.NpxTool

class YamlCodeProjectLoader(
    private val gradleOutputParser: GradleOutputParser,
    private val npxOutputParser: NpxOutputParser?,
) : CodeProjectLoader {
  override suspend fun loadProject(
      projectDirectory: ReadonlyCompatFsDirectory,
      projectPath: LiteralAbsoluteUnixPath,
  ): CodeProject =
      YamlCodeProject(
          rootModule = loadModule(moduleDirectory = projectDirectory, modulePath = projectPath),
      )

  private suspend fun loadModule(
      moduleDirectory: ReadonlyCompatFsDirectory,
      modulePath: LiteralAbsoluteUnixPath,
  ): YamlCodeModule {
    val config = readModuleConfig(moduleDirectory = moduleDirectory)

    val submodulesByName =
        config.submodules.mapValues { (_, relativeModulePathText) ->
          val relativeModulePath = parseLiteralRelativePath(relativeModulePathText)
          val submoduleDirectory =
              moduleDirectory.extractDeep(relativePath = relativeModulePath)
                  as? ReadonlyCompatFsDirectory
                  ?: error("Expected submodule directory at $relativeModulePathText")

          loadModule(
              moduleDirectory = submoduleDirectory,
              modulePath = modulePath.resolve(relativeModulePath),
          )
        }

    val formattingTools = buildList {
      addAll(
          config.facets.values.mapNotNull { facet ->
            facet.formatting
                ?.fix
                ?.toTool(
                    modulePath = modulePath,
                    gradleOutputParser = gradleOutputParser,
                    npxOutputParser = npxOutputParser,
                )
          },
      )
      addAll(submodulesByName.values.map { it.formattingTool })
    }

    val formattingTool = formattingTools.toSequentialCodeTool()

    val verificationTools = buildList {
      addAll(
          config.facets.values.flatMap { facet ->
            facet.verification.map { verification ->
              verification.run.toTool(
                  modulePath = modulePath,
                  gradleOutputParser = gradleOutputParser,
                  npxOutputParser = npxOutputParser,
              )
            }
          },
      )
      addAll(submodulesByName.values.map { it.verificationTool })
    }

    val verificationTool = verificationTools.toSequentialCodeTool()

    return YamlCodeModule(
        formattingTool = formattingTool,
        verificationTool = verificationTool,
        submodulesByName = submodulesByName,
    )
  }

  private suspend fun readModuleConfig(
      moduleDirectory: ReadonlyCompatFsDirectory
  ): ModuleYamlConfig {
    val moduleYamlFile =
        moduleDirectory.extract(moduleYamlFilePath.names.single()) as? ReadonlyCompatFsFile
            ?: error("Expected module.yaml in module directory")

    val parsedYamlObject = yaml.load<Any?>(moduleYamlFile.readText())

    return json.decodeFromJsonElement(
        deserializer = ModuleYamlConfig.serializer(),
        element = parsedYamlObject.toJsonElement(),
    )
  }

  companion object {
    private val yaml = Yaml()
    private val json = Json { ignoreUnknownKeys = true }

    private val moduleYamlFilePath =
        LiteralRelativeUnixPath.of(
            UnixPath.Name.Literal("module.yaml"),
        )

    private fun parseLiteralRelativePath(relativePathText: String): LiteralRelativeUnixPath =
        RelativeUnixPath.parse(relativePathText).let { parsedPath ->
          val literalNames = parsedPath.names.map { pathName -> pathName as? UnixPath.Name.Literal }

          checkNotNull(literalNames.takeIf { names -> names.all { it != null } }?.map { it!! }) {
                "Expected literal relative path, got: $relativePathText"
              }
              .let { literalNamesOnly -> LiteralRelativeUnixPath(names = literalNamesOnly) }
        }
  }
}

@Serializable
private data class ModuleYamlConfig(
    val submodules: Map<String, String> = emptyMap(),
    val facets: Map<String, FacetConfig> = emptyMap(),
) {
  @Serializable
  data class FacetConfig(
      val formatting: FormattingConfig? = null,
      val verification: List<VerificationConfig> = emptyList(),
  )

  @Serializable
  data class FormattingConfig(
      val fix: ModuleYamlRunConfig,
  )

  @Serializable
  data class VerificationConfig(
      val name: String,
      val run: ModuleYamlRunConfig,
  )
}

@Serializable(with = ModuleYamlRunConfigSerializer::class)
private sealed interface ModuleYamlRunConfig {
  @Serializable
  data class GradleTask(
      val gradleTask: String,
  ) : ModuleYamlRunConfig

  @Serializable
  data class NpxArgs(
      val npxArgs: List<String>,
  ) : ModuleYamlRunConfig
}

private object ModuleYamlRunConfigSerializer : KSerializer<ModuleYamlRunConfig> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ModuleYamlRunConfig")

  override fun serialize(encoder: Encoder, value: ModuleYamlRunConfig) {
    require(encoder is JsonEncoder)

    val element =
        when (value) {
          is ModuleYamlRunConfig.GradleTask ->
              buildJsonObject {
                put(
                    "gradle-task",
                    encoder.json
                        .encodeToJsonElement(
                            serializer = ModuleYamlRunConfig.GradleTask.serializer(),
                            value = value,
                        )
                        .jsonObject
                        .getValue("gradleTask"),
                )
              }

          is ModuleYamlRunConfig.NpxArgs ->
              buildJsonObject {
                put(
                    "npx-args",
                    encoder.json
                        .encodeToJsonElement(
                            serializer = ModuleYamlRunConfig.NpxArgs.serializer(),
                            value = value,
                        )
                        .jsonObject
                        .getValue("npxArgs"),
                )
              }
        }

    encoder.encodeJsonElement(element)
  }

  override fun deserialize(decoder: Decoder): ModuleYamlRunConfig {
    require(decoder is JsonDecoder)

    val jsonObject = decoder.decodeJsonElement().jsonObject

    require(jsonObject.size == 1) { "Expected exactly one run config key, got: ${jsonObject.keys}" }

    return when {
      "gradle-task" in jsonObject ->
          ModuleYamlRunConfig.GradleTask(
              gradleTask = jsonObject.getValue("gradle-task").requireJsonString(),
          )

      "npx-args" in jsonObject ->
          ModuleYamlRunConfig.NpxArgs(
              npxArgs = jsonObject.getValue("npx-args").requireJsonStringList(),
          )

      else -> error("Unknown run config variant: ${jsonObject.keys}")
    }
  }
}

private fun ModuleYamlRunConfig.toTool(
    modulePath: LiteralAbsoluteUnixPath,
    gradleOutputParser: GradleOutputParser,
    npxOutputParser: NpxOutputParser?,
): CodeTool =
    when (this) {
      is ModuleYamlRunConfig.GradleTask ->
          GradleTool(
              projectPath = modulePath,
              taskName = gradleTask,
              gradleOutputParser = gradleOutputParser,
          )

      is ModuleYamlRunConfig.NpxArgs ->
          NpxTool(
              projectPath = modulePath,
              args = npxArgs,
              npxOutputParser = npxOutputParser,
          )
    }

private class SequentialCodeTool(
    private val tools: List<CodeTool>,
) : CodeTool {
  override suspend fun diagnose(): CodeTool.CodeModuleDiagnosis {
    val incorrectDiagnoses = tools.mapNotNull { tool ->
      tool.diagnose() as? CodeTool.CodeModuleDiagnosis.Incorrect
    }

    return when {
      incorrectDiagnoses.isEmpty() -> CodeTool.CodeModuleDiagnosis.Correct
      else ->
          CodeTool.CodeModuleDiagnosis.Incorrect(
              diagnosisByFilePath =
                  incorrectDiagnoses
                      .flatMap { diagnosis -> diagnosis.diagnosisByFilePath.entries }
                      .groupBy(keySelector = { it.key }, valueTransform = { it.value })
                      .mapValues { (_, diagnoses) ->
                        CodeTool.CodeFileDiagnosis(
                            issues = diagnoses.flatMap { it.issues }.distinct(),
                        )
                      },
          )
    }
  }
}

private fun List<CodeTool>.toSequentialCodeTool(): CodeTool =
    when (size) {
      0 -> CodeTool.AlwaysCorrect
      1 -> single()
      else -> SequentialCodeTool(tools = this)
    }

private fun Any?.toJsonElement(): JsonElement =
    when (this) {
      null -> JsonNull
      is JsonElement -> this
      is String -> JsonPrimitive(this)
      is Number -> JsonPrimitive(this)
      is Boolean -> JsonPrimitive(this)
      is Map<*, *> ->
          JsonObject(
              entries.associate { (key, value) ->
                (key as? String ?: error("Expected string YAML map key, got: $key")) to
                    value.toJsonElement()
              },
          )
      is List<*> -> JsonArray(map { element -> element.toJsonElement() })
      else -> error("Unsupported YAML value: $this")
    }

private fun JsonElement.requireJsonString(): String =
    (this as? JsonPrimitive)?.content ?: error("Expected JSON string, got: $this")

private fun JsonElement.requireJsonStringList(): List<String> =
    (this as? JsonArray)?.map { element -> element.requireJsonString() }
        ?: error("Expected JSON string list, got: $this")

private suspend fun ReadonlyCompatFsDirectory.extractDeep(
    relativePath: LiteralRelativeUnixPath,
): ReadonlyCompatFsEntity? {
  var currentEntity: ReadonlyCompatFsEntity = this

  for (name in relativePath.names) {
    currentEntity = (currentEntity as? ReadonlyCompatFsDirectory)?.extract(name) ?: return null
  }

  return currentEntity
}
