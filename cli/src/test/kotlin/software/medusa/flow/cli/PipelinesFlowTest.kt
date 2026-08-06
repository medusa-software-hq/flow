package software.medusa.flow.cli

import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.grpc.GrpcService
import io.grpc.Status
import io.grpc.StatusException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import software.medusa.flow.v1.ClearIssuePipelineRequest
import software.medusa.flow.v1.ClearIssuePipelineResponse
import software.medusa.flow.v1.IssuePipeline
import software.medusa.flow.v1.IssuePipelineState
import software.medusa.flow.v1.IssuePipelineTransition
import software.medusa.flow.v1.ListIssuePipelinesRequest
import software.medusa.flow.v1.ListIssuePipelinesResponse
import software.medusa.flow.v1.PipelineServiceGrpcKt
import software.medusa.flow.v1.WatchIssuePipelinesRequest
import software.medusa.flow.v1.clearIssuePipelineResponse
import software.medusa.flow.v1.issuePipeline
import software.medusa.flow.v1.issuePipelineTransition
import software.medusa.flow.v1.listIssuePipelinesResponse

/**
 * Exercises the real code the `flow pipelines` commands run on top of
 * ([FlowApiClient] + [formatPipelineTable]) against a fake
 * [PipelineService][PipelineServiceGrpcKt], the way `PipelinesListCommand`/`PipelinesClearCommand`
 * themselves do (`withFlowApiClient { it.xyz() }` then format/echo) — auth and credential
 * resolution are the only layers not exercised here (see [FlowApiClientTest] for the bearer-header
 * piece).
 *
 * Regression coverage for the bug this pins down: `list`'s table must print the *pipeline* id (not
 * just the session id), because that's the identifier `clear` accepts — otherwise an operator has
 * no CLI-only way to clear a FAILED pipeline (see `formatPipelineTable`'s ID column).
 */
class PipelinesFlowTest {
  /** A minimal in-memory [PipelineService][PipelineServiceGrpcKt], just enough to seed one row. */
  private class FakePipelineService : PipelineServiceGrpcKt.PipelineServiceCoroutineImplBase() {
    val pipeline =
        AtomicReference(
            issuePipeline {
              id = "pipe-123"
              repoFullName = "acme/app"
              issueNumber = 42
              issueTitle = "Fix the mutex leak"
              state = IssuePipelineState.ISSUE_PIPELINE_STATE_FAILED
              sessionId = "sess-abc"
            },
        )

    override suspend fun listIssuePipelines(
        request: ListIssuePipelinesRequest,
    ): ListIssuePipelinesResponse = listIssuePipelinesResponse { pipelines += pipeline.get() }

    override suspend fun clearIssuePipeline(
        request: ClearIssuePipelineRequest,
    ): ClearIssuePipelineResponse {
      val current = pipeline.get()
      if (request.id != current.id) {
        throw Status.NOT_FOUND.withDescription("No such pipeline: ${request.id}")
            .asRuntimeException()
      }
      if (current.state != IssuePipelineState.ISSUE_PIPELINE_STATE_FAILED) {
        throw Status.FAILED_PRECONDITION.withDescription("Not FAILED").asRuntimeException()
      }
      val cleared: IssuePipeline = current.toBuilder().setCleared(true).build()
      pipeline.set(cleared)
      return clearIssuePipelineResponse { this.pipeline = cleared }
    }

    override fun watchIssuePipelines(
        request: WatchIssuePipelinesRequest,
    ): Flow<IssuePipelineTransition> {
      val current = pipeline.get()
      return flowOf(
          issuePipelineTransition {
            this.pipeline = current
            oldState = IssuePipelineState.ISSUE_PIPELINE_STATE_IN_PROGRESS
          },
      )
    }
  }

  private val fakeService = FakePipelineService()
  private val server =
      Server.builder()
          .http(0)
          .serviceUnder("/", GrpcService.builder().addService(fakeService).build())
          .build()
          .also { it.start().join() }

  private val client by lazy {
    FlowApiClient.create(
        apiUrl = "http://127.0.0.1:${server.activeLocalPort()}/",
        idToken = "test-token",
    )
  }

  @AfterTest
  fun tearDown() {
    server.stop().join()
  }

  @Test
  fun `list surfaces the pipeline id, and clear accepts exactly that id`() = runBlocking {
    // `flow pipelines list`: the printed table must carry the id `clear` needs — an operator can't
    // guess it from the session id alone.
    val listed = client.listIssuePipelines()
    val table = formatPipelineTable(listed)
    assertTrue(table.lines()[0].startsWith("ID"))
    assertTrue(table.contains("pipe-123"))

    val idFromList = listed.single().id
    assertEquals("pipe-123", idFromList)

    // `flow pipelines clear <id copied from list>`.
    val cleared = client.clearIssuePipeline(idFromList)
    assertTrue(cleared.cleared)
    assertEquals("acme/app", cleared.repoFullName)
    assertEquals(42, cleared.issueNumber)

    // The session id alone (what the table used to lead with) is not an id `clear` accepts.
    val failure = assertFailsWith<StatusException> { client.clearIssuePipeline("sess-abc") }
    assertEquals(Status.Code.NOT_FOUND, failure.status.code)
  }

  @Test
  fun `watch streams a transition for the pipeline`() = runBlocking {
    val transition = withTimeout(5000) { client.watchIssuePipelines().first() }

    assertEquals("pipe-123", transition.pipeline.id)
    assertEquals(IssuePipelineState.ISSUE_PIPELINE_STATE_IN_PROGRESS, transition.oldState)
    assertEquals(IssuePipelineState.ISSUE_PIPELINE_STATE_FAILED, transition.pipeline.state)
  }
}
