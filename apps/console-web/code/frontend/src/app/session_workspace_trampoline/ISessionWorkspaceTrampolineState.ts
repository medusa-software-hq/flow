import { ISessionWorkspace } from '../session_workspace/ISessionWorkspace';
import {
  SessionWorkspaceTrampolineStateKinds,
  TSessionWorkspaceTrampolineStateKind,
} from './SessionWorkspaceTrampolineStateKinds';

export interface ISessionWorkspaceTrampolineState {
  readonly kind: TSessionWorkspaceTrampolineStateKind;
}

export interface ICreatingState extends ISessionWorkspaceTrampolineState {
  readonly kind: typeof SessionWorkspaceTrampolineStateKinds.Creating;
}

export interface IOperationalState extends ISessionWorkspaceTrampolineState {
  readonly kind: typeof SessionWorkspaceTrampolineStateKinds.Operational;
  readonly operationalSessionWorkspace: ISessionWorkspace;
}

export interface ICreationFailedState extends ISessionWorkspaceTrampolineState {
  readonly kind: typeof SessionWorkspaceTrampolineStateKinds.Failed;
  readonly error: unknown;

  retry(): void;
}

export type USessionWorkspaceTrampolineState =
  | ICreatingState
  | IOperationalState
  | ICreationFailedState;
