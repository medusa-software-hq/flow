import { ISessionWorkspace } from '@/app/ISessionWorkspace';
import { AppTrampolineStateKinds, TAppTrampolineStateKind } from '@/app_trampoline/AppStateKinds';

export interface IAppTrampolineState {
  readonly kind: TAppTrampolineStateKind;
}

export interface ILoadingState extends IAppTrampolineState {
  readonly kind: typeof AppTrampolineStateKinds.Loading;
}

export interface ILoadedState extends IAppTrampolineState {
  readonly kind: typeof AppTrampolineStateKinds.Loaded;
  readonly loadedSessionWorkspace: ISessionWorkspace;
}

export interface IFailedState extends IAppTrampolineState {
  readonly kind: typeof AppTrampolineStateKinds.Failed;
  readonly error: unknown;

  retry(): void;
}

export type UAppTrampolineState = ILoadingState | ILoadedState | IFailedState;
