package software.medusa.flow.worker

import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind

/**
 * Placeholder [WrkSessionProcessor]: appends one event and completes with a placeholder PR URL,
 * without running the engine or touching GitHub. Removed once the real pipeline (stories 13/14)
 * lands.
 */
object WrkStubSessionProcessor : WrkSessionProcessor {
  private const val placeholderPrUrl = "https://example.com/flow-worker-stub-pr"

  override suspend fun process(
      session: Session,
      apiClient: WrkApiClient,
  ) {
    apiClient.appendSessionEvent(
        sessionId = session.id,
        kind = SessionEventKind.SESSION_EVENT_KIND_WORKSPACE_PREPARING,
        message =
            "Stub worker: no-op processing. The engine pipeline and PR publishing land in " +
                "later stories.",
    )

    apiClient.completeSession(sessionId = session.id, prUrl = placeholderPrUrl)
  }
}
