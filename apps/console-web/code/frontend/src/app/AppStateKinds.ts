export const AppStateKinds = {
  Editing: 'editing',
  Running: 'running',
} as const;

export type TAppStateKind = (typeof AppStateKinds)[keyof typeof AppStateKinds];
