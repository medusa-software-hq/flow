package software.medusa.flow.server

/**
 * Test convenience: starts a shadow-less pipeline (matching production, which no longer fans out a
 * built-in shadow session — see [ReconcilePicker]), for the many store/reconcile tests where the
 * shadow is incidental. It resolves by arity (5 args) alongside the real 6-arg
 * [IssuePipelineStore.pick]; tests that exercise the shadow linkage itself pass a shadow session id
 * explicitly to the real method instead.
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
        shadowSessionId = null,
    )
