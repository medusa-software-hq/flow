import { ISessionWorkspace } from '@/app/ISessionWorkspace';
import {
  SessionWorkspaceTrampolineStateKinds,
  TSessionWorkspaceTrampolineStateKind,
} from './SessionWorkspaceTrampolineStateKinds';

export interface ISessionWorkspaceTrampolineState {
  readonly kind: TSessionWorkspaceTrampolineStateKind;
}

export interface ILoadingState extends ISessionWorkspaceTrampolineState {
  readonly kind: typeof SessionWorkspaceTrampolineStateKinds.Loading;
}

export interface ILoadedState extends ISessionWorkspaceTrampolineState {
  readonly kind: typeof SessionWorkspaceTrampolineStateKinds.Loaded;
  readonly loadedSessionWorkspace: ISessionWorkspace;
}

export interface IFailedState extends ISessionWorkspaceTrampolineState {
  readonly kind: typeof SessionWorkspaceTrampolineStateKinds.Failed;
  readonly error: unknown;

  retry(): void;
}

export type USessionWorkspaceTrampolineState = ILoadingState | ILoadedState | IFailedState;
