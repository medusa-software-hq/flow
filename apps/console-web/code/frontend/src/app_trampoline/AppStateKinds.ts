export const AppTrampolineStateKinds = {
  Loading: 'loading',
  Loaded: 'loaded',
  Failed: 'failed',
} as const;

export type TAppTrampolineStateKind =
  (typeof AppTrampolineStateKinds)[keyof typeof AppTrampolineStateKinds];
