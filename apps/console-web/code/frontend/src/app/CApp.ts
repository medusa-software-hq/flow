import { IEditingState, IRunningState, UAppState } from '@/app/IAppState';
import { CRunningSession } from '@/app/session/running_session/CRunningSession';
import { CSessionEditor } from '@/app/session_editor/CSessionEditor';
import { AppStateKinds } from './AppStateKinds';
import { IApp, IAppSessionSummary } from './IApp';

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const fakeSessions: readonly IAppSessionSummary[] = [
  { id: 'current', label: 'i', tone: 'blue', isSelected: true },
  { id: 'vim', label: 'v', tone: 'lime', isSelected: false },
  { id: 'notes', label: '■', tone: 'ink', isSelected: false },
  { id: 'zap-1', label: 'z', tone: 'violet', isSelected: false },
  { id: 'g', label: 'g', tone: 'pink', isSelected: false },
  { id: 'zap-2', label: 'z', tone: 'violet', isSelected: false },
];

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

  get sessions(): readonly IAppSessionSummary[] {
    return fakeSessions;
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
