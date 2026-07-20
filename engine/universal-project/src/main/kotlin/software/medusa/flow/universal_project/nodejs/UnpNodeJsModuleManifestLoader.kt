package software.medusa.flow.universal_project.nodejs

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.integration.nodejs.NjsCommand
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.nodejs.UnpNodeJsModuleManifestLoader.RawNodeJsModuleDetails
import software.medusa.flow.universal_project.nodejs.UnpNodeJsModuleManifestLoader.RawNodeJsStepDetails
import software.medusa.flow.universal_project.yaml.UnpYamlModuleManifestLoader

data object UnpNodeJsModuleManifestLoader :
    UnpYamlModuleManifestLoader<RawNodeJsModuleDetails, RawNodeJsStepDetails>() {
  @Serializable
  enum class RawNodeJsPackageManager {
    @SerialName("npm") Npm,
    @SerialName("yarn") Yarn;

    fun toBaked(): NjsPackageManager =
        when (this) {
          Npm -> NjsPackageManager.Npm
          Yarn -> NjsPackageManager.Yarn
        }
  }

  @Serializable
  data class RawNodeJsModuleDetails(
      @SerialName("package-manager") val packageManager: RawNodeJsPackageManager,
  )

  @Serializable
  data class RawNodeJsStepDetails(
      val command: String,
      val args: List<String> = emptyList(),
  )

  override val filePrefix: UfsName.Literal = UfsName.Literal("nodejs")

  override val moduleDetailsSerializer: KSerializer<RawNodeJsModuleDetails> =
      RawNodeJsModuleDetails.serializer()

  override val stepDetailsSerializer: KSerializer<RawNodeJsStepDetails> =
      RawNodeJsStepDetails.serializer()

  override suspend fun bindModule(
      rawModuleManifest: RawModuleManifest<RawNodeJsModuleDetails, RawNodeJsStepDetails>,
      physicalWorkspace: PhwWorkspace,
      modulePath: UfsLiteralAbsolutePath,
  ): BoundModule {
    val packageConnection =
        physicalWorkspace.connectNodeJs(
            packageManager = rawModuleManifest.details.packageManager.toBaked(),
            packagePath = modulePath,
        )

    // Installing dependencies is a prerequisite for the module's own steps, not one of them: it is
    // what makes the project-local commands (`tsc`, `jest`, …) resolvable in the first place.
    packageConnection.installDependencies()

    val nodeJsCommandByName: Map<String, NjsCommand> =
        rawModuleManifest.allJobs
            .flatMap { job -> job.steps }
            .map { step -> UfsName.Literal(step.details.command) }
            .toSet()
            .associate { commandName ->
              val resolvedCommand =
                  packageConnection.resolveCommand(name = commandName)
                      ?: throw IllegalArgumentException(
                          "Command could not be resolved: ${commandName.content}",
                      )

              commandName.content to resolvedCommand
            }

    return BoundModule.build(
        rawModuleManifest = rawModuleManifest,
        jobBinder =
            object : JobBinder<RawNodeJsStepDetails> {
              override fun bindJob(rawJob: RawJob<RawNodeJsStepDetails>): BoundJob =
                  object : BoundJob {
                    override suspend fun execute(): UnpModuleConnection.Result {
                      rawJob.steps.forEach { rawStep ->
                        // Every step's command was resolved up front when the module was bound.
                        val nodeJsCommand =
                            checkNotNull(nodeJsCommandByName[rawStep.details.command])

                        val executionResult =
                            nodeJsCommand.execute(arguments = rawStep.details.args)

                        if (executionResult.exitCode != 0) {
                          return UnpModuleConnection.Result.Failure(
                              diagnosticOutput =
                                  executionResult.standardOutput + executionResult.errorOutput,
                          )
                        }
                      }

                      return UnpModuleConnection.Result.Success
                    }
                  }
            },
    )
  }
}
