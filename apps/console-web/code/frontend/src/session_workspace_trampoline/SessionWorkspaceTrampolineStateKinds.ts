export const SessionWorkspaceTrampolineStateKinds = {
  Loading: 'loading',
  Loaded: 'loaded',
  Failed: 'failed',
} as const;

export type TSessionWorkspaceTrampolineStateKind =
  (typeof SessionWorkspaceTrampolineStateKinds)[keyof typeof SessionWorkspaceTrampolineStateKinds];
