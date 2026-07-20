package software.medusa.flow.universal_project

import software.medusa.commons.unix.path.UfsLiteralAbsolutePath

/**
 * A universal project bound to a physical workspace: every module is connected and a given
 * lifecycle phase can be run across all of them at once.
 */
class UnpProjectConnection(
    private val moduleConnectionByPath: Map<UfsLiteralAbsolutePath, UnpModuleConnection>,
) {
  /** The combined outcome of running a lifecycle phase across all of a project's modules. */
  sealed class JointResult {
    /** Every module succeeded. */
    data object Success : JointResult()

    /** At least one module failed; the rest may have succeeded. */
    data class Failure(
        val failureByModulePath: Map<UfsLiteralAbsolutePath, UnpModuleConnection.Result.Failure>,
    ) : JointResult() {
      init {
        require(failureByModulePath.isNotEmpty()) {
          "At least one module must have failed for a joint failure result"
        }
      }
    }

    companion object {
      /**
       * Collapses per-module results into a [Success] or a [Failure] carrying the failed modules.
       */
      fun interpret(
          resultByModulePath: Map<UfsLiteralAbsolutePath, UnpModuleConnection.Result>,
      ): JointResult {
        val failureByModulePath =
            resultByModulePath
                .asSequence()
                .mapNotNull { (modulePath, moduleResult) ->
                  val moduleFailure =
                      moduleResult as? UnpModuleConnection.Result.Failure ?: return@mapNotNull null

                  modulePath to moduleFailure
                }
                .toMap()

        return when {
          failureByModulePath.isEmpty() -> Success
          else -> Failure(failureByModulePath = failureByModulePath)
        }
      }
    }
  }

  /** Bootstraps every module. */
  suspend fun bootstrapAll(): JointResult =
      JointResult.interpret(
          resultByModulePath =
              moduleConnectionByPath.mapValues { (_, moduleConnection) ->
                moduleConnection.bootstrap()
              },
      )

  /** Analyzes every module. */
  suspend fun analyzeAll(): JointResult =
      JointResult.interpret(
          resultByModulePath =
              moduleConnectionByPath.mapValues { (_, moduleConnection) ->
                moduleConnection.analyze()
              },
      )

  /** Tests every module. */
  suspend fun testAll(): JointResult =
      JointResult.interpret(
          resultByModulePath =
              moduleConnectionByPath.mapValues { (_, moduleConnection) -> moduleConnection.test() },
      )

  /** Normalizes every module. */
  suspend fun normalizeAll(): JointResult =
      JointResult.interpret(
          resultByModulePath =
              moduleConnectionByPath.mapValues { (_, moduleConnection) ->
                moduleConnection.normalize()
              },
      )
}
