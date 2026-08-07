package software.medusa.flow.worker

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import com.linecorp.armeria.client.grpc.GrpcClients
import io.grpc.auth.MoreCallCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind
import software.medusa.flow.v1.SessionWriteAck
import software.medusa.flow.v1.WorkerServiceGrpcKt
import software.medusa.flow.v1.appendSessionEventRequest
import software.medusa.flow.v1.claimNextJobRequest
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
 * not per call — but that internal refresh is timer-driven off the token's own expiry, not
 * failure-driven: if a call comes back UNAUTHENTICATED (expired/rejected token, or the refresh
 * itself silently failed) the library has no reason to mint a new one before its own timer says so,
 * so the caller must force it via [invalidateCredentials].
 */
class WrkGrpcApiClient
private constructor(
    private val stub: WorkerServiceGrpcKt.WorkerServiceCoroutineStub,
    private val idTokenCredentials: IdTokenCredentials? = null,
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
        lookupEnv: (String) -> String? = System::getenv,
    ): WrkGrpcApiClient {
      val baseStub =
          GrpcClients.builder(apiUrl)
              .build(WorkerServiceGrpcKt.WorkerServiceCoroutineStub::class.java)

      if (lookupEnv(skipAuthEnvVarName) != null) {
        return WrkGrpcApiClient(stub = baseStub)
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

      return WrkGrpcApiClient(stub = authenticatedStub, idTokenCredentials = idTokenCredentials)
    }
  }

  /**
   * Blocking network call — [IdTokenCredentials.refresh] unconditionally mints a new token via the
   * underlying [IdTokenProvider] and replaces the cached one, regardless of the library's own
   * FRESH/STALE/EXPIRED view of the old one's expiry. That's the point: a 401 means the cached
   * token is already bad by the control plane's judgment, not the library's, so its normal
   * expiry-timer refresh can't be trusted to fix it. No-op when auth is skipped (test seam).
   */
  override suspend fun invalidateCredentials() {
    idTokenCredentials?.let { credentials -> withContext(Dispatchers.IO) { credentials.refresh() } }
  }

  // Workers are uniform (every worker runs every engine), so the claim is unconditional — the
  // request carries no capability set.
  override suspend fun claimNextSession(): Session? =
      stub.claimNextSession(claimNextSessionRequest {}).sessionOrNull

  override suspend fun claimNextJob(): List<Session> =
      stub.claimNextJob(claimNextJobRequest {}).sessionsList

  override suspend fun registerWorker(
      workerId: String,
      workerVersion: String,
      imageDigest: String,
  ) {
    stub.registerWorker(
        registerWorkerRequest {
          this.workerId = workerId
          this.workerVersion = workerVersion
          this.imageDigest = imageDigest
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

  override suspend fun heartbeat(sessionId: String): Boolean {
    val response = stub.heartbeat(heartbeatRequest { this.sessionId = sessionId })
    return response.ack == SessionWriteAck.SESSION_WRITE_ACK_ABORTED
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
      workerDeath: Boolean,
  ) {
    stub.failSession(
        failSessionRequest {
          this.sessionId = sessionId
          this.failureSummary = failureSummary
          this.workerDeath = workerDeath
        },
    )
  }
}
