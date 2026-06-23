package software.medusa.flow.universal_project.yaml

import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyFile
import software.medusa.commons.unix.filesystem.extractDeepReadonly
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.universal_project.UnpModuleManifestLoader
import software.medusa.flow.universal_project.UnpProjectManifest
import software.medusa.flow.universal_project.UnpProjectManifestLoader
import software.medusa.yaml.Yaml

class UnpYamlProjectManifestLoader(
    private val gradleModuleManifestLoader: UnpModuleManifestLoader,
    private val nodeJsModuleManifestLoader: UnpModuleManifestLoader,
) : UnpProjectManifestLoader {
  @Serializable
  private data class RawProjectManifest(
      val modules: List<Module>,
  ) {
    @Serializable
    enum class Kind {
      @SerialName("gradle") Gradle,
      @SerialName("nodejs") NodeJs,
    }

    @Serializable
    data class Module(
        val kind: Kind,
        val name: String,
        val path: String,
    )
  }

  companion object {
    private val projectManifestFileName = UfsName.Literal("project.yaml")
  }

  override suspend fun load(
      projectDirectory: UfsReadonlyDirectory,
  ): UnpProjectManifest {
    val projectManifestFile =
        projectDirectory.extract(name = projectManifestFileName) as? UfsReadonlyFile
            ?: throw IllegalArgumentException("Project manifest file not found")

    val projectManifestYamlString = projectManifestFile.read().decodeToString()
    val projectManifestJsonElement = Yaml.decodeFromString(projectManifestYamlString)

    val rawProjectManifest =
        Json.decodeFromJsonElement(
            deserializer = RawProjectManifest.serializer(),
            element = projectManifestJsonElement,
        )

    val moduleManifestByPath =
        rawProjectManifest.modules
            .groupBy { it.path }
            .asSequence()
            .associate { (rawPath, rawModules) ->
              val rawModule =
                  rawModules.singleOrNull()
                      ?: throw IllegalArgumentException(
                          "Multiple module manifests found for path: $rawPath",
                      )

              val modulePath =
                  UfsAbsolutePath.parse(rawPath).toLiteral()
                      ?: throw IllegalArgumentException("Invalid module path: ${rawModule.path}")

              val moduleDirectory =
                  projectDirectory.extractDeepReadonly(
                      relativePath = modulePath.innerPath,
                  ) as? UfsReadonlyDirectory
                      ?: throw IllegalArgumentException(
                          "Module directory not found for path: ${rawModule.path}"
                      )

              val moduleManifestLoader =
                  when (rawModule.kind) {
                    RawProjectManifest.Kind.Gradle -> gradleModuleManifestLoader
                    RawProjectManifest.Kind.NodeJs -> nodeJsModuleManifestLoader
                  }

              modulePath to
                  moduleManifestLoader.load(
                      moduleDirectory = moduleDirectory,
                  )
            }

    return UnpProjectManifest(
        moduleManifestByPath,
    )
  }
}
