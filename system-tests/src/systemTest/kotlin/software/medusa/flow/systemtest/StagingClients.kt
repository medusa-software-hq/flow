package software.medusa.flow.systemtest

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import com.google.auth.oauth2.ImpersonatedCredentials
import com.linecorp.armeria.client.grpc.GrpcClients
import io.grpc.auth.MoreCallCredentials
import software.medusa.flow.v1.ReconcileServiceGrpcKt
import software.medusa.flow.v1.SessionServiceGrpcKt
import software.medusa.flow.v1.WorkerServiceGrpcKt

/**
 * Typed, authenticated gRPC clients against deployed staging — the same construction the worker
 * uses ([software.medusa.flow.worker.WrkGrpcApiClient], mirrored here rather than depended on to
 * keep this module a standalone black-box client): an Armeria coroutine stub with a Google ID token
 * (audience = the API URL) attached as call credentials.
 *
 * One identity serves every service: a worker-audience token passes the auth decorator, and
 * `SessionService` requires only a validly-signed token (no worker allowlist), so the same token
 * drives both `SessionService` and the worker-authorized `WorkerService` (the `smoke.sh` posture).
 */
class StagingClients
private constructor(
    val sessionService: SessionServiceGrpcKt.SessionServiceCoroutineStub,
    val workerService: WorkerServiceGrpcKt.WorkerServiceCoroutineStub,
    val reconcileService: ReconcileServiceGrpcKt.ReconcileServiceCoroutineStub,
) {
  companion object {
    private const val cloudPlatformScope = "https://www.googleapis.com/auth/cloud-platform"

    fun forConfig(
        config: StagingConfig,
    ): StagingClients {
      val callCredentials = MoreCallCredentials.from(buildIdTokenCredentials(config))

      val sessionService =
          GrpcClients.builder(config.apiUrl)
              .build(SessionServiceGrpcKt.SessionServiceCoroutineStub::class.java)
              .withCallCredentials(callCredentials)

      val workerService =
          GrpcClients.builder(config.apiUrl)
              .build(WorkerServiceGrpcKt.WorkerServiceCoroutineStub::class.java)
              .withCallCredentials(callCredentials)

      val reconcileService =
          GrpcClients.builder(config.apiUrl)
              .build(ReconcileServiceGrpcKt.ReconcileServiceCoroutineStub::class.java)
              .withCallCredentials(callCredentials)

      return StagingClients(sessionService, workerService, reconcileService)
    }

    private fun buildIdTokenCredentials(
        config: StagingConfig,
    ): IdTokenCredentials {
      val source = GoogleCredentials.getApplicationDefault()

      // When a worker SA is configured, impersonate it so the API sees a worker-class identity
      // (the gate path: ADC is the CI/CD SA, which has tokenCreator on the worker SA). Otherwise
      // use ADC directly (a developer machine whose ADC is already an authorized identity).
      val idTokenProvider: IdTokenProvider =
          if (config.workerSaEmail != null) {
            ImpersonatedCredentials.newBuilder()
                .setSourceCredentials(source)
                .setTargetPrincipal(config.workerSaEmail)
                .setScopes(listOf(cloudPlatformScope))
                .build()
          } else {
            source as? IdTokenProvider
                ?: error(
                    "Application Default Credentials do not support ID tokens; set WORKER_SA_EMAIL " +
                        "to impersonate the worker SA, or authenticate ADC as a service account.",
                )
          }

      // Pass both options so the token carries the `email` claim whichever way ADC resolves — the
      // IAM/impersonation path honours INCLUDE_EMAIL, the metadata path only adds it for
      // FORMAT_FULL (the exact reasoning in WrkGrpcApiClient).
      return IdTokenCredentials.newBuilder()
          .setIdTokenProvider(idTokenProvider)
          .setTargetAudience(config.apiUrl)
          .setOptions(
              listOf(IdTokenProvider.Option.INCLUDE_EMAIL, IdTokenProvider.Option.FORMAT_FULL)
          )
          .build()
    }
  }
}
