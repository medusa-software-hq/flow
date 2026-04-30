import { AppStateKinds, TAppStateKind } from './AppStateKinds';
import { IRunningSession } from './session/ISession';
import { ISessionEditor } from './session_editor/ISessionEditor';

export interface IAppState {
  readonly kind: TAppStateKind;
}

export interface IEditingState extends IAppState {
  readonly kind: typeof AppStateKinds.Editing;
  readonly sessionEditor: ISessionEditor;
}

export interface IRunningState extends IAppState {
  readonly kind: typeof AppStateKinds.Running;
  readonly runningSession: IRunningSession;
}

export type UAppState = IEditingState | IRunningState;
