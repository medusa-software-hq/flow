package software.medusa.flow.worker

import java.nio.file.Path
import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace
import software.medusa.flow.v1.Engine

/**
 * Test convenience: the pre-dual-engine 7-arg [WrkPublisher.publish] signature, defaulting the
 * engine to Claude. Existing tests (for which the engine is incidental) bind here by arity; the
 * dual-engine tests call the real 8-arg method with an explicit [Engine].
 */
suspend fun WrkPublisher.publish(
    repoFullName: String,
    sessionId: String,
    taskHeading: String,
    taskMarkdown: String,
    cloneDirectory: Path,
    workspace: HrsReadonlyTemporaryWorkspace,
    issueNumber: Int?,
): WrkPublishResult =
    publish(
        repoFullName = repoFullName,
        sessionId = sessionId,
        taskHeading = taskHeading,
        taskMarkdown = taskMarkdown,
        cloneDirectory = cloneDirectory,
        workspace = workspace,
        issueNumber = issueNumber,
        engine = Engine.ENGINE_CLAUDE,
    )
