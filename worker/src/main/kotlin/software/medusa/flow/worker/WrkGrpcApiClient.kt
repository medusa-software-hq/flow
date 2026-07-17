package software.medusa.flow.worker

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import com.linecorp.armeria.client.grpc.GrpcClients
import io.grpc.auth.MoreCallCredentials
import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind
import software.medusa.flow.v1.WorkerServiceGrpcKt
import software.medusa.flow.v1.appendSessionEventRequest
import software.medusa.flow.v1.claimNextSessionRequest
import software.medusa.flow.v1.completeSessionRequest
import software.medusa.flow.v1.failSessionRequest
import software.medusa.flow.v1.heartbeatRequest
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
      // and user credentials all do). INCLUDE_EMAIL is required: without it the minted token has no
      // `email` claim, which the control plane's auth decorator requires.
      val idTokenProvider = GoogleCredentials.getApplicationDefault() as IdTokenProvider

      val idTokenCredentials =
          IdTokenCredentials.newBuilder()
              .setIdTokenProvider(idTokenProvider)
              .setTargetAudience(apiUrl)
              .setOptions(listOf(IdTokenProvider.Option.INCLUDE_EMAIL))
              .build()

      val authenticatedStub =
          baseStub.withCallCredentials(MoreCallCredentials.from(idTokenCredentials))

      return WrkGrpcApiClient(stub = authenticatedStub)
    }
  }

  override suspend fun claimNextSession(): Session? =
      stub.claimNextSession(claimNextSessionRequest {}).sessionOrNull

  override suspend fun appendSessionEvent(
      sessionId: String,
      kind: SessionEventKind,
      message: String,
  ) {
    stub.appendSessionEvent(
        appendSessionEventRequest {
          this.sessionId = sessionId
          this.kind = kind
          this.message = message
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
