export const SessionWorkspaceStateKinds = {
  Editing: 'editing',
  Running: 'running',
} as const;

export type TSessionWorkspaceStateKind =
  (typeof SessionWorkspaceStateKinds)[keyof typeof SessionWorkspaceStateKinds];
