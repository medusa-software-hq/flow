import { IEditingState, IRunningState, USessionWorkspaceState } from '@/app/ISessionWorkspaceState';
import { CRunningSession } from '@/app/session/running_session/CRunningSession';
import { CSessionEditor } from '@/app/session_editor/CSessionEditor';
import { sleep } from '../utils/promiseUtils';
import { ISessionWorkspace } from './ISessionWorkspace';
import { SessionWorkspaceStateKinds } from './SessionWorkspaceStateKinds';

export class CSessionWorkspace implements ISessionWorkspace {
  static async load(): Promise<ISessionWorkspace> {
    const initialSessionEditor = CSessionEditor.create();

    const initialState: IEditingState = {
      kind: SessionWorkspaceStateKinds.Editing,
      sessionEditor: initialSessionEditor,
    };

    await sleep(2000);

    if (Math.random() < 0.2) {
      throw new Error('Random session error!!!1');
    }

    return new CSessionWorkspace(initialState);
  }

  _currentState: USessionWorkspaceState;

  private constructor(initialState: IEditingState) {
    this._currentState = initialState;
  }

  get currentState(): USessionWorkspaceState {
    return this._currentState;
  }

  freeze(): void {
    const currentState = this.currentState;

    if (currentState.kind !== SessionWorkspaceStateKinds.Editing) {
      throw new Error('Cannot freeze app state: current state is not editing');
    }

    const sessionEditor = currentState.sessionEditor;

    const frozenRunningSession = CRunningSession.freeze(sessionEditor.editedSession);

    const frozenState: IRunningState = {
      kind: SessionWorkspaceStateKinds.Running,
      runningSession: frozenRunningSession,
    };

    this._currentState = frozenState;
  }
}
