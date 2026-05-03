import { IApp } from '@/app/IApp';
import { AppTrampolineStateKinds, TAppTrampolineStateKind } from '@/app_trampoline/AppStateKinds';

export interface IAppTrampolineState {
  readonly kind: TAppTrampolineStateKind;
}

export interface ILoadingState extends IAppTrampolineState {
  readonly kind: typeof AppTrampolineStateKinds.Loading;
}

export interface ILoadedState extends IAppTrampolineState {
  readonly kind: typeof AppTrampolineStateKinds.Loaded;
  readonly loadedApp: IApp;
}

export interface ILoadingFailedState extends IAppTrampolineState {
  readonly kind: typeof AppTrampolineStateKinds.Failed;
  readonly error: unknown;

  reload(): void;
}

export type UAppTrampolineState = ILoadingState | ILoadedState | ILoadingFailedState;
