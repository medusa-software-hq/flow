package software.medusa.flow.server

import com.linecorp.armeria.client.grpc.GrpcClients
import io.grpc.Status
import io.grpc.StatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.flow.v1.Engine as ProtoEngine
import software.medusa.flow.v1.SessionServiceGrpcKt
import software.medusa.flow.v1.SessionState as ProtoSessionState
import software.medusa.flow.v1.createSessionRequest
import software.medusa.flow.v1.getSessionRequest
import software.medusa.flow.v1.listSessionsRequest

/**
 * Exercises [SessionServiceImpl] through a real in-process Armeria server and gRPC client, so it
 * covers the full wiring: request-context email propagation into the coroutine handler
 * ([AuthenticatedUser]), proto mapping, and the store's lazy expiry surfacing through the API.
 */
class SessionServiceImpl_serverTests {
  /** A [Clock] whose instant can be advanced, so heartbeat expiry is deterministic. */
  private class MutableClock(
      var current: Instant,
      private val zone: ZoneId = ZoneOffset.UTC,
  ) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
      current = current.plus(duration)
    }
  }

  private val heartbeatTimeout = Duration.ofMinutes(3)
  private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
  private val store = InMemorySessionStore(clock = clock, heartbeatTimeout = heartbeatTimeout)

  private val server =
      buildServer(
              originRegex = ".*",
              port = 0,
              auth = NoOpAuthDecorator,
              gitHubIssueStore = FakeGitHubIssueStore(),
              gitHubRepositoryStore = FakeGitHubRepositoryStore(),
              sessionStore = store,
              workerAuthorizer = WorkerAuthorizer.permissive,
          )
          .also { it.start().join() }

  private val client =
      GrpcClients.newClient(
          "http://127.0.0.1:${server.activeLocalPort()}/",
          SessionServiceGrpcKt.SessionServiceCoroutineStub::class.java,
      )

  @AfterTest
  fun tearDown() {
    server.stop().join()
  }

  @Test
  fun `create then list then get round-trips and stamps created_by from auth`() = runBlocking {
    val created =
        client.createSession(
            createSessionRequest {
              repoFullName = "acme/app"
              taskMarkdown = "# Task"
            },
        )

    assertEquals("acme/app", created.session.repoFullName)
    assertEquals(ProtoSessionState.SESSION_STATE_PENDING, created.session.state)
    // NoOpAuthDecorator's placeholder identity.
    assertEquals("local@localhost", created.session.createdBy)
    assertTrue(created.session.id.isNotEmpty())

    val listed = client.listSessions(listSessionsRequest {})
    assertEquals(listOf(created.session.id), listed.sessionsList.map { it.id })

    val got = client.getSession(getSessionRequest { id = created.session.id })
    assertEquals(created.session.id, got.session.id)
    assertEquals("# Task", got.session.taskMarkdown)
  }

  @Test
  fun `createSession round-trips a requested engine`() = runBlocking {
    val created =
        client.createSession(
            createSessionRequest {
              repoFullName = "acme/app"
              taskMarkdown = "# Task"
              engine = ProtoEngine.ENGINE_CLAUDE
            },
        )

    assertEquals(ProtoEngine.ENGINE_CLAUDE, created.session.engine)

    val got = client.getSession(getSessionRequest { id = created.session.id })
    assertEquals(ProtoEngine.ENGINE_CLAUDE, got.session.engine)
  }

  @Test
  fun `createSession with no engine defaults to UNSPECIFIED`() = runBlocking {
    val created =
        client.createSession(
            createSessionRequest {
              repoFullName = "acme/app"
              taskMarkdown = "# Task"
            },
        )

    assertEquals(ProtoEngine.ENGINE_UNSPECIFIED, created.session.engine)
  }

  @Test
  fun `invalid repo_full_name and empty task are rejected`() = runBlocking {
    assertFailsWith<StatusException> {
      client.createSession(
          createSessionRequest {
            repoFullName = "not-a-repo"
            taskMarkdown = "# Task"
          },
      )
    }

    assertFailsWith<StatusException> {
      client.createSession(
          createSessionRequest {
            repoFullName = "acme/app"
            taskMarkdown = "   "
          },
      )
    }
  }

  @Test
  fun `getSession on an unknown id fails with NOT_FOUND`() = runBlocking {
    val failure =
        assertFailsWith<StatusException> {
          client.getSession(getSessionRequest { id = "does-not-exist" })
        }

    assertEquals(Status.Code.NOT_FOUND, failure.status.code)
  }

  @Test
  fun `a running session with a stale heartbeat reads back as failed`() = runBlocking {
    val created =
        client.createSession(
            createSessionRequest {
              repoFullName = "acme/app"
              taskMarkdown = "# Task"
            },
        )

    // Claim it directly on the store to move PENDING → RUNNING, then let the heartbeat go stale.
    store.claimNext()
    clock.advance(heartbeatTimeout.plusSeconds(1))

    val got = client.getSession(getSessionRequest { id = created.session.id })
    assertEquals(ProtoSessionState.SESSION_STATE_FAILED, got.session.state)
    assertEquals(SessionStore.workerLostSummary, got.session.failureSummary)
  }
}
