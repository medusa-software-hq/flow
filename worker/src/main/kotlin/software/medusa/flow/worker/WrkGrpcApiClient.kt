package software.medusa.flow.worker

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import com.linecorp.armeria.client.grpc.GrpcClients
import io.grpc.auth.MoreCallCredentials
import software.medusa.flow.v1.ClaimNextSessionRequest
import software.medusa.flow.v1.Engine
import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind
import software.medusa.flow.v1.WorkerServiceGrpcKt
import software.medusa.flow.v1.appendSessionEventRequest
import software.medusa.flow.v1.claimNextSessionRequest
import software.medusa.flow.v1.completeSessionRequest
import software.medusa.flow.v1.failSessionRequest
import software.medusa.flow.v1.heartbeatRequest
import software.medusa.flow.v1.registerWorkerRequest
import software.medusa.flow.v1.sessionOrNull

/**
 * Real [WrkApiClient]: mints a Google ID token (audience = [apiUrl]) from Application Default
 * Credentials and attaches it to every call. In practice that's short-lived credentials from
 * impersonating the worker SA (`gcloud auth application-default login
 * --impersonate-service-account=...`, see `worker/scripts/get-worker-credentials.sh`) — the only
 * supported path, deliberately: no downloaded long-lived key file, ever. `google-auth-library`'s
 * [IdTokenCredentials] caches/refreshes the token internally, so minting only happens on expiry,
 * not per call.
 */
class WrkGrpcApiClient
private constructor(
    private val stub: WorkerServiceGrpcKt.WorkerServiceCoroutineStub,
    private val supportedEngines: List<Engine>,
) : WrkApiClient {
  companion object {
    /**
     * Test-only: skip ID-token minting entirely, for a worker talking to a local control plane with
     * no Google credentials around (the hermetic loop test). This weakens nothing — it only makes
     * the *client* send no credentials. Authorization is enforced server-side by the auth decorator
     * and [WorkerAuthorizer] allowlist, so a worker that sets this against a real deployment is
     * simply rejected; it cannot talk its way in.
     */
    private const val skipAuthEnvVarName = "FLOW_TEST_SKIP_API_AUTH"

    fun create(
        apiUrl: String,
        supportedEngines: List<Engine> = emptyList(),
        lookupEnv: (String) -> String? = System::getenv,
    ): WrkGrpcApiClient {
      val baseStub =
          GrpcClients.builder(apiUrl)
              .build(WorkerServiceGrpcKt.WorkerServiceCoroutineStub::class.java)

      if (lookupEnv(skipAuthEnvVarName) != null) {
        return WrkGrpcApiClient(stub = baseStub, supportedEngines = supportedEngines)
      }

      // ADC must resolve to something implementing IdTokenProvider (impersonated, service-account,
      // compute-engine, and user credentials all do). The control plane's auth decorator requires
      // the `email` claim, and *which option produces it depends on the credential type*: the
      // IAM/impersonation + SA-key path honours INCLUDE_EMAIL, while the compute-engine metadata
      // path (a GCE VM, or ms-workload's Beacon emulating one) only adds `?format=full` — the thing
      // that embeds the email — for FORMAT_FULL, ignoring INCLUDE_EMAIL. Pass both so the token
      // carries the email whichever way ADC resolves; each type takes the one it understands.
      val idTokenProvider = GoogleCredentials.getApplicationDefault() as IdTokenProvider

      val idTokenCredentials =
          IdTokenCredentials.newBuilder()
              .setIdTokenProvider(idTokenProvider)
              .setTargetAudience(apiUrl)
              .setOptions(
                  listOf(
                      IdTokenProvider.Option.INCLUDE_EMAIL,
                      IdTokenProvider.Option.FORMAT_FULL,
                  ),
              )
              .build()

      val authenticatedStub =
          baseStub.withCallCredentials(MoreCallCredentials.from(idTokenCredentials))

      return WrkGrpcApiClient(stub = authenticatedStub, supportedEngines = supportedEngines)
    }

    /**
     * Builds the claim request declaring the worker's engine capabilities (M4). Pure, so A6's unit
     * test can assert on it without a live stub. An empty list keeps the pre-M4 "claim any session"
     * behavior.
     */
    internal fun buildClaimNextSessionRequest(
        supportedEngines: List<Engine>,
    ): ClaimNextSessionRequest = claimNextSessionRequest {
      this.supportedEngines.addAll(supportedEngines)
    }
  }

  override suspend fun claimNextSession(): Session? =
      stub.claimNextSession(buildClaimNextSessionRequest(supportedEngines)).sessionOrNull

  override suspend fun registerWorker(
      workerId: String,
      workerVersion: String,
      imageDigest: String,
      supportedEngines: List<Engine>,
  ) {
    stub.registerWorker(
        registerWorkerRequest {
          this.workerId = workerId
          this.workerVersion = workerVersion
          this.imageDigest = imageDigest
          this.supportedEngines.addAll(supportedEngines)
        },
    )
  }

  override suspend fun appendSessionEvent(
      sessionId: String,
      kind: SessionEventKind,
      message: String,
      costUsd: Double?,
  ) {
    stub.appendSessionEvent(
        appendSessionEventRequest {
          this.sessionId = sessionId
          this.kind = kind
          this.message = message
          costUsd?.let { this.costUsd = it }
        },
    )
  }

  override suspend fun heartbeat(sessionId: String) {
    stub.heartbeat(heartbeatRequest { this.sessionId = sessionId })
  }

  override suspend fun completeSession(
      sessionId: String,
      prUrl: String,
  ) {
    stub.completeSession(
        completeSessionRequest {
          this.sessionId = sessionId
          this.prUrl = prUrl
        },
    )
  }

  override suspend fun failSession(
      sessionId: String,
      failureSummary: String,
  ) {
    stub.failSession(
        failSessionRequest {
          this.sessionId = sessionId
          this.failureSummary = failureSummary
        },
    )
  }
}
