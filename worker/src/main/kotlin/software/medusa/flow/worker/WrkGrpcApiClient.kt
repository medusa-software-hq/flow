package software.medusa.flow.worker

import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import com.google.auth.oauth2.ServiceAccountCredentials
import com.linecorp.armeria.client.grpc.GrpcClients
import io.grpc.auth.MoreCallCredentials
import java.io.FileInputStream
import java.nio.file.Path
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
 * Real [WrkApiClient]: mints a Google ID token from the service-account key (audience = [apiUrl])
 * and attaches it to every call. `google-auth-library`'s [IdTokenCredentials] caches/refreshes the
 * token internally, so minting only happens on expiry, not per call.
 */
class WrkGrpcApiClient
private constructor(
    private val stub: WorkerServiceGrpcKt.WorkerServiceCoroutineStub,
) : WrkApiClient {
  companion object {
    fun create(
        apiUrl: String,
        workerSaKeyFile: Path,
    ): WrkGrpcApiClient {
      val serviceAccountCredentials =
          FileInputStream(workerSaKeyFile.toFile()).use { keyFileStream ->
            ServiceAccountCredentials.fromStream(keyFileStream)
          } as IdTokenProvider

      val idTokenCredentials =
          IdTokenCredentials.newBuilder()
              .setIdTokenProvider(serviceAccountCredentials)
              .setTargetAudience(apiUrl)
              .build()

      val baseStub =
          GrpcClients.builder(apiUrl)
              .build(WorkerServiceGrpcKt.WorkerServiceCoroutineStub::class.java)

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
