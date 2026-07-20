package software.medusa.flow.universal_project

/**
 * A single module of a universal project bound to a physical workspace, ready to run the lifecycle
 * phases declared in its manifest.
 *
 * Dependency installation is not one of these phases: it is a toolchain-level prerequisite
 * performed when the module is bound (see the per-toolchain manifest loaders), so by the time these
 * methods run the module's tooling is already available.
 */
interface UnpModuleConnection {
  /** Outcome of running a lifecycle phase. */
  sealed class Result {
    /** The phase completed without any step reporting a failure. */
    data object Success : Result()

    /** A step failed. */
    data class Failure(
        /** Combined human-readable output of the failing step, for surfacing to the user. */
        val diagnosticOutput: String,
    ) : Result()
  }

  /**
   * Runs the user-defined steps that make the checked-out repository usable for development beyond
   * installing dependencies — for example source/code generation. Succeeds trivially when the
   * manifest declares no bootstrap steps.
   */
  suspend fun bootstrap(): Result

  /**
   * Runs the non-mutating correctness checks (type-checking, linting, assembling, …). Succeeds
   * trivially when the manifest declares no analyze steps.
   */
  suspend fun analyze(): Result

  /** Runs the module's tests. Succeeds trivially when the manifest declares no test steps. */
  suspend fun test(): Result

  /**
   * Runs the mutating counterpart of [analyze]: steps that rewrite the working tree into a
   * canonical form (formatting, lint auto-fixes, …). Succeeds trivially when the manifest declares
   * no normalize steps.
   */
  suspend fun normalize(): Result
}
