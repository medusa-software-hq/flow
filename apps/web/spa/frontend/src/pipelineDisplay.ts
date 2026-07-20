import { IssuePipelineState } from './gen/medusa/pipeline/v1/pipeline_service_pb.ts';

export const pipelineStateLabel: Record<IssuePipelineState, string> = {
  [IssuePipelineState.UNSPECIFIED]: 'Unknown',
  [IssuePipelineState.IN_PROGRESS]: 'In progress',
  [IssuePipelineState.PR_OPEN]: 'PR open',
  [IssuePipelineState.AWAITING_MERGE_CHECKS]: 'Merge checks',
  [IssuePipelineState.DONE]: 'Done',
  [IssuePipelineState.FAILED]: 'Failed',
};

export const pipelineStateColor: Record<IssuePipelineState, string> = {
  [IssuePipelineState.UNSPECIFIED]: 'gray',
  [IssuePipelineState.IN_PROGRESS]: 'blue',
  [IssuePipelineState.PR_OPEN]: 'cyan',
  [IssuePipelineState.AWAITING_MERGE_CHECKS]: 'violet',
  [IssuePipelineState.DONE]: 'green',
  [IssuePipelineState.FAILED]: 'red',
};

/**
 * The repo is waiting on GitHub for this pipeline (PR review, or the post-merge checks) — surfaced
 * as the busy-repo hint. IN_PROGRESS is excluded: that shows as its own live session row.
 */
export function isAwaitingGitHub(state: IssuePipelineState): boolean {
  return state === IssuePipelineState.PR_OPEN || state === IssuePipelineState.AWAITING_MERGE_CHECKS;
}
