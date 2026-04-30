import { IRunningSession } from './session/ISession';
import { ISessionEditor } from './session_editor/ISessionEditor';
import {
  SessionWorkspaceStateKinds,
  TSessionWorkspaceStateKind,
} from './SessionWorkspaceStateKinds';

export interface ISessionWorkspaceState {
  readonly kind: TSessionWorkspaceStateKind;
}

export interface IEditingState extends ISessionWorkspaceState {
  readonly kind: typeof SessionWorkspaceStateKinds.Editing;
  readonly sessionEditor: ISessionEditor;
}

export interface IRunningState extends ISessionWorkspaceState {
  readonly kind: typeof SessionWorkspaceStateKinds.Running;
  readonly runningSession: IRunningSession;
}

export type USessionWorkspaceState = IEditingState | IRunningState;
