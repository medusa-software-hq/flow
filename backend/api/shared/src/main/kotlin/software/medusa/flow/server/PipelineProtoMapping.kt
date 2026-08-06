package software.medusa.flow.server

import software.medusa.flow.v1.IssuePipeline as ProtoIssuePipeline
import software.medusa.flow.v1.IssuePipelineState as ProtoIssuePipelineState
import software.medusa.flow.v1.issuePipeline

/** Conversions between the storage [IssuePipeline] domain type and its generated proto type. */
internal fun IssuePipelineState.toProto(): ProtoIssuePipelineState =
    when (this) {
      IssuePipelineState.InProgress -> ProtoIssuePipelineState.ISSUE_PIPELINE_STATE_IN_PROGRESS
      IssuePipelineState.PrOpen -> ProtoIssuePipelineState.ISSUE_PIPELINE_STATE_PR_OPEN
      IssuePipelineState.AwaitingMergeChecks ->
          ProtoIssuePipelineState.ISSUE_PIPELINE_STATE_AWAITING_MERGE_CHECKS
      IssuePipelineState.Done -> ProtoIssuePipelineState.ISSUE_PIPELINE_STATE_DONE
      IssuePipelineState.Failed -> ProtoIssuePipelineState.ISSUE_PIPELINE_STATE_FAILED
    }

/**
 * [outboxStuck] is computed by the service from [GithubOutboxStore.stuckEntries] rather than stored
 * on the row, so it's passed in. `pr_number`/`merge_commit_sha` are internal bookkeeping and have
 * no proto field; `cleared` collapses `cleared_at` to a bool.
 */
fun IssuePipeline.toProto(
    outboxStuck: Boolean = false,
): ProtoIssuePipeline {
  val domain = this

  return issuePipeline {
    id = domain.id.id
    repoFullName = domain.repoFullName
    issueNumber = domain.issueNumber
    issueTitle = domain.issueTitle
    issueUrl = domain.issueUrl
    state = domain.state.toProto()
    // Proto3 strings default to empty; nulls collapse to "".
    sessionId = domain.sessionId?.id.orEmpty()
    shadowSessionId = domain.shadowSessionId?.id.orEmpty()
    prUrl = domain.prUrl.orEmpty()
    failureSummary = domain.failureSummary.orEmpty()
    createdAt = domain.createdAt.toProtoTimestamp()
    updatedAt = domain.updatedAt.toProtoTimestamp()
    cleared = domain.clearedAt != null
    this.outboxStuck = outboxStuck
  }
}
