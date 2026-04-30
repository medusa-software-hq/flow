import { IEditingState, IRunningState, UAppState } from '@/app/IAppState';
import { CRunningSession } from '@/app/session/running_session/CRunningSession';
import { CSessionEditor } from '@/app/session_editor/CSessionEditor';
import { AppStateKinds } from './AppStateKinds';
import { IApp } from './IApp';

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export class CApp implements IApp {
  static async load(): Promise<IApp> {
    const initialSessionEditor = CSessionEditor.create();

    const initialState: IEditingState = {
      kind: AppStateKinds.Editing,
      sessionEditor: initialSessionEditor,
    };

    await sleep(2000);

    if (Math.random() < 0.1) {
      throw new Error('Random error!!!1');
    }

    return new CApp(initialState);
  }

  _currentState: UAppState;

  private constructor(initialState: IEditingState) {
    this._currentState = initialState;
  }

  get currentState(): UAppState {
    return this._currentState;
  }

  freeze(): void {
    const currentState = this.currentState;

    if (currentState.kind !== AppStateKinds.Editing) {
      throw new Error('Cannot freeze app state: current state is not editing');
    }

    const sessionEditor = currentState.sessionEditor;

    const frozenRunningSession = CRunningSession.freeze(sessionEditor.editedSession);

    const frozenState: IRunningState = {
      kind: AppStateKinds.Running,
      runningSession: frozenRunningSession,
    };

    this._currentState = frozenState;
  }
}
