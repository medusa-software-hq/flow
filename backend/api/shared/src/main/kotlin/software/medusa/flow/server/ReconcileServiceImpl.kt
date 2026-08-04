package software.medusa.flow.server

import io.grpc.Status
import software.medusa.flow.v1.ReconcileRequest
import software.medusa.flow.v1.ReconcileResponse
import software.medusa.flow.v1.ReconcileServiceGrpcKt
import software.medusa.flow.v1.reconcileResponse
import software.medusa.flow.v1.repoReconcileSummary

/**
 * Internal `ReconcileService`: drives [Reconciler] and is gated to service-account callers on the
 * [reconcileAuthorizer] allowlist (the scheduler SA and the worker SA), the same mechanism
 * [WorkerServiceImpl] uses. A validly-signed *user* token is rejected with `PERMISSION_DENIED`.
 */
class ReconcileServiceImpl(
    private val reconciler: Reconciler,
    private val reconcileAuthorizer: WorkerAuthorizer,
) : ReconcileServiceGrpcKt.ReconcileServiceCoroutineImplBase() {
  override suspend fun reconcile(
      request: ReconcileRequest,
  ): ReconcileResponse {
    requireAuthorizedCaller()

    val repoFilter = request.repoFullName.takeIf { it.isNotEmpty() }
    val summaries = reconciler.reconcile(repoFilter)

    return reconcileResponse {
      summaries.forEach { summary ->
        repoSummaries.add(
            repoReconcileSummary {
              repoFullName = summary.repoFullName
              pickedCount = summary.pickedCount
              advancedCount = summary.advancedCount
              drainedCount = summary.drainedCount
            },
        )
      }
    }
  }

  private fun requireAuthorizedCaller() {
    val email =
        AuthenticatedUser.currentEmail()
            ?: throw Status.UNAUTHENTICATED.withDescription("No authenticated caller")
                .asRuntimeException()

    if (!reconcileAuthorizer.isAuthorized(email)) {
      throw Status.PERMISSION_DENIED.withDescription("$email may not call ReconcileService")
          .asRuntimeException()
    }
  }
}
