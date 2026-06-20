package software.medusa.flow.universal_project.gradle

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.integration.gradle.GrdTaskName
import software.medusa.flow.integration.gradle.GrdTaskResult
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.gradle.UnpGradleModuleManifestLoader.RawGradleStepDetails
import software.medusa.flow.universal_project.yaml.UnpYamlModuleManifestLoader

data object UnpGradleModuleManifestLoader :
    UnpYamlModuleManifestLoader<Unit, RawGradleStepDetails>() {
  @Serializable
  data class RawGradleStepDetails(
      val task: String,
  )

  override val filePrefix: UfsName.Literal = UfsName.Literal("gradle")

  override val moduleDetailsSerializer: KSerializer<Unit> = Unit.serializer()

  override val stepDetailsSerializer: KSerializer<RawGradleStepDetails> =
      RawGradleStepDetails.serializer()

  override suspend fun bindModule(
      rawModuleManifest: RawModuleManifest<Unit, RawGradleStepDetails>,
      physicalWorkspace: PhwWorkspace,
      modulePath: UfsLiteralAbsolutePath,
  ): BoundModule {
    val projectConnection =
        physicalWorkspace.connectGradle(
            projectPath = modulePath,
        )

    return BoundModule.build(
        rawModuleManifest = rawModuleManifest,
        jobBinder =
            object : JobBinder<RawGradleStepDetails> {
              override fun bindJob(rawJob: RawJob<RawGradleStepDetails>): BoundJob =
                  object : BoundJob {
                    override suspend fun execute(): UnpModuleConnection.Result {
                      rawJob.steps.forEach { rawStep ->
                        val taskResult =
                            projectConnection.runTask(
                                taskName = GrdTaskName(name = rawStep.details.task),
                            )

                        when (taskResult.status) {
                          GrdTaskResult.Status.Success -> {}
                          GrdTaskResult.Status.Failure -> {
                            return UnpModuleConnection.Result.Failure(
                                diagnosticOutput =
                                    taskResult.standardOutput + taskResult.errorOutput,
                            )
                          }
                        }
                      }

                      return UnpModuleConnection.Result.Success
                    }
                  }
            },
    )
  }
}
