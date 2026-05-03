export const SessionWorkspaceTrampolineStateKinds = {
  Creating: 'creating',
  Operational: 'operational',
  Failed: 'failed',
} as const;

export type TSessionWorkspaceTrampolineStateKind =
  (typeof SessionWorkspaceTrampolineStateKinds)[keyof typeof SessionWorkspaceTrampolineStateKinds];
