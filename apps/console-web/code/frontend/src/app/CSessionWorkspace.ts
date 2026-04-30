import { IEditingState, IRunningState, USessionWorkspaceState } from '@/app/ISessionWorkspaceState';
import { CRunningSession } from '@/app/session/running_session/CRunningSession';
import { CSessionEditor } from '@/app/session_editor/CSessionEditor';
import { ISessionWorkspace, ISessionWorkspaceSummary } from './ISessionWorkspace';
import { SessionWorkspaceStateKinds } from './SessionWorkspaceStateKinds';

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const fakeSessions: readonly ISessionWorkspaceSummary[] = [
  { id: 'current', label: 'i', tone: 'blue', isSelected: true },
  { id: 'vim', label: 'v', tone: 'lime', isSelected: false },
  { id: 'notes', label: '■', tone: 'ink', isSelected: false },
  { id: 'zap-1', label: 'z', tone: 'violet', isSelected: false },
  { id: 'g', label: 'g', tone: 'pink', isSelected: false },
  { id: 'zap-2', label: 'z', tone: 'violet', isSelected: false },
];

export class CSessionWorkspace implements ISessionWorkspace {
  static async load(): Promise<ISessionWorkspace> {
    const initialSessionEditor = CSessionEditor.create();

    const initialState: IEditingState = {
      kind: SessionWorkspaceStateKinds.Editing,
      sessionEditor: initialSessionEditor,
    };

    await sleep(2000);

    if (Math.random() < 0.1) {
      throw new Error('Random error!!!1');
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

  get sessions(): readonly ISessionWorkspaceSummary[] {
    return fakeSessions;
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
