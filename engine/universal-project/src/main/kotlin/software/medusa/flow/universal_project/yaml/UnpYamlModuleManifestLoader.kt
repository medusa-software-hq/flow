package software.medusa.flow.universal_project.yaml

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyFile
import software.medusa.commons.unix.filesystem.readText
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpModuleManifest
import software.medusa.flow.universal_project.UnpModuleManifestLoader
import software.medusa.flow.universal_project.yaml.UnpYamlModuleManifestLoader.BoundJob.Companion.executeOrSuccess
import software.medusa.yaml.Yaml

abstract class UnpYamlModuleManifestLoader<RawModuleDetailsT : Any, RawStepDetailsT : Any> :
    UnpModuleManifestLoader {
  internal data class RawModuleManifest<RawModuleDetailsT : Any, RawStepDetailsT : Any>(
      val details: RawModuleDetailsT,
      val bootstrapJob: RawJob<RawStepDetailsT>?,
      val analyzeJob: RawJob<RawStepDetailsT>?,
      val testJob: RawJob<RawStepDetailsT>?,
      val normalizeJob: RawJob<RawStepDetailsT>?,
  ) {
    val allJobs: List<RawJob<RawStepDetailsT>>
      get() =
          listOfNotNull(
              bootstrapJob,
              analyzeJob,
              testJob,
              normalizeJob,
          )
  }

  internal data class RawJob<RawStepDetailsT : Any>(
      val steps: List<RawStep<RawStepDetailsT>>,
  )

  internal data class RawStep<RawStepDetailsT : Any>(
      val name: String,
      val details: RawStepDetailsT,
  )

  internal interface BoundModule {
    companion object {
      fun <RawStepDetailsT : Any> build(
          rawModuleManifest: RawModuleManifest<*, RawStepDetailsT>,
          jobBinder: JobBinder<RawStepDetailsT>,
      ): BoundModule =
          object : BoundModule {
            override val bootstrapJob: BoundJob? =
                rawModuleManifest.bootstrapJob?.let { jobBinder.bindJob(rawJob = it) }

            override val analyzeJob: BoundJob? =
                rawModuleManifest.analyzeJob?.let { jobBinder.bindJob(rawJob = it) }

            override val testJob: BoundJob? =
                rawModuleManifest.testJob?.let { jobBinder.bindJob(rawJob = it) }

            override val normalizeJob: BoundJob? =
                rawModuleManifest.normalizeJob?.let { jobBinder.bindJob(rawJob = it) }
          }
    }

    val bootstrapJob: BoundJob?
    val analyzeJob: BoundJob?
    val testJob: BoundJob?
    val normalizeJob: BoundJob?
  }

  internal interface BoundJob {
    companion object {
      suspend fun BoundJob?.executeOrSuccess(): UnpModuleConnection.Result {
        val self = this ?: return UnpModuleConnection.Result.Success
        return self.execute()
      }
    }

    suspend fun execute(): UnpModuleConnection.Result
  }

  internal interface JobBinder<RawStepDetailsT : Any> {
    fun bindJob(
        rawJob: RawJob<RawStepDetailsT>,
    ): BoundJob
  }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    private const val bootstrapKey = "bootstrap"
    private const val analyzeKey = "analyze"
    private const val testKey = "test"
    private const val normalizeKey = "normalize"

    private const val stepsKey = "steps"
    private const val nameKeyName = "name"
  }

  final override suspend fun load(
      moduleDirectory: UfsReadonlyDirectory,
  ): UnpModuleManifest {
    val manifestFile =
        moduleDirectory.extract(
            UfsName.Literal.concat(
                listOf(filePrefix, UfsName.Literal(".module.yaml")),
            ),
        ) as? UfsReadonlyFile ?: throw IllegalArgumentException("Module manifest file not found")

    val manifestYamlText = manifestFile.readText()
    val manifestJsonElement = Yaml.decodeFromString(manifestYamlText)

    val manifestJsonObject =
        manifestJsonElement as? JsonObject
            ?: throw IllegalArgumentException("Module manifest is not a JSON object")

    val moduleDetails =
        json.decodeFromJsonElement(
            deserializer = moduleDetailsSerializer,
            element = manifestJsonElement,
        )

    val rawModuleManifest =
        RawModuleManifest(
            details = moduleDetails,
            bootstrapJob = manifestJsonObject[bootstrapKey]?.let { loadRawJob(jobElement = it) },
            analyzeJob = manifestJsonObject[analyzeKey]?.let { loadRawJob(jobElement = it) },
            testJob = manifestJsonObject[testKey]?.let { loadRawJob(jobElement = it) },
            normalizeJob = manifestJsonObject[normalizeKey]?.let { loadRawJob(jobElement = it) },
        )

    return object : UnpModuleManifest {
      override suspend fun connect(
          physicalWorkspace: PhwWorkspace,
          modulePath: UfsLiteralAbsolutePath,
      ): UnpModuleConnection {
        val boundModule: BoundModule =
            bindModule(
                rawModuleManifest = rawModuleManifest,
                physicalWorkspace = physicalWorkspace,
                modulePath = modulePath,
            )

        return object : UnpModuleConnection {
          override suspend fun bootstrap(): UnpModuleConnection.Result =
              boundModule.bootstrapJob.executeOrSuccess()

          override suspend fun analyze(): UnpModuleConnection.Result =
              boundModule.analyzeJob.executeOrSuccess()

          override suspend fun test(): UnpModuleConnection.Result =
              boundModule.testJob.executeOrSuccess()

          override suspend fun normalize(): UnpModuleConnection.Result =
              boundModule.normalizeJob.executeOrSuccess()
        }
      }
    }
  }

  private fun loadRawJob(
      jobElement: JsonElement,
  ): RawJob<RawStepDetailsT> {
    val jobObject =
        jobElement as? JsonObject
            ?: throw IllegalArgumentException("Job element is not a JSON object")

    val stepsElement =
        jobObject[stepsKey]
            ?: throw IllegalArgumentException("Job element does not contain a '$stepsKey' field")

    val stepsArray =
        stepsElement as? JsonArray
            ?: throw IllegalArgumentException("Job '$stepsKey' field is not a JSON array")

    return RawJob(
        steps = stepsArray.map { stepElement -> loadRawStep(stepElement) },
    )
  }

  private fun loadRawStep(
      stepElement: JsonElement,
  ): RawStep<RawStepDetailsT> {
    val stepObject =
        stepElement as? JsonObject
            ?: throw IllegalArgumentException("Step element is not a JSON object")

    val nameElement =
        stepObject[nameKeyName]
            ?: throw IllegalArgumentException(
                "Step element does not contain a '$nameKeyName' field"
            )

    val namePrimitive =
        nameElement as? JsonPrimitive
            ?: throw IllegalArgumentException("Step '$nameKeyName' field is not a JSON primitive")

    val name = namePrimitive.content

    val details =
        json.decodeFromJsonElement(
            deserializer = stepDetailsSerializer,
            element = stepObject,
        )

    return RawStep(
        name = name,
        details = details,
    )
  }

  abstract val filePrefix: UfsName.Literal

  internal abstract val moduleDetailsSerializer: KSerializer<RawModuleDetailsT>

  internal abstract val stepDetailsSerializer: KSerializer<RawStepDetailsT>

  internal abstract suspend fun bindModule(
      rawModuleManifest: RawModuleManifest<RawModuleDetailsT, RawStepDetailsT>,
      physicalWorkspace: PhwWorkspace,
      modulePath: UfsLiteralAbsolutePath,
  ): BoundModule
}
