package software.medusa.flow.server

/**
 * Test convenience: starts a pipeline with an auto-derived built-in shadow session id, for the many
 * store/reconcile tests where the shadow is incidental. It resolves by arity (5 args) alongside the
 * real 6-arg [IssuePipelineStore.pick]; tests that actually exercise dual-engine fan-out pass a
 * shadow session id explicitly to the real method instead.
 */
suspend fun IssuePipelineStore.pick(
    repoFullName: String,
    issueNumber: Int,
    issueTitle: String,
    issueUrl: String,
    sessionId: SessionId,
): PickResult =
    pick(
        repoFullName = repoFullName,
        issueNumber = issueNumber,
        issueTitle = issueTitle,
        issueUrl = issueUrl,
        sessionId = sessionId,
        shadowSessionId = SessionId("${sessionId.id}-shadow"),
    )
