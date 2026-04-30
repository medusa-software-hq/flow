import { proxy } from 'valtio';
import { CSessionWorkspace } from '@/app/CSessionWorkspace';
import { ISessionWorkspaceTrampoline } from './ISessionWorkspaceTrampoline';
import {
  IFailedState,
  ILoadedState,
  ILoadingState,
  USessionWorkspaceTrampolineState,
} from './ISessionWorkspaceTrampolineState';
import { SessionWorkspaceTrampolineStateKinds } from './SessionWorkspaceTrampolineStateKinds';

export class CSessionWorkspaceTrampoline implements ISessionWorkspaceTrampoline {
  static createProxied(): ISessionWorkspaceTrampoline {
    const self: CSessionWorkspaceTrampoline = proxy(new CSessionWorkspaceTrampoline());

    self._initialize();

    return self;
  }

  private _currentState: USessionWorkspaceTrampolineState = {
    kind: SessionWorkspaceTrampolineStateKinds.Loading,
  };

  private _initialize() {
    void this._tryLoadingSessionWorkspace();
  }

  private async _tryLoadingSessionWorkspace() {
    const loadingState: ILoadingState = {
      kind: SessionWorkspaceTrampolineStateKinds.Loading,
    };

    this._currentState = loadingState;

    try {
      const loadedSessionWorkspace = await CSessionWorkspace.load();

      const loadedState: ILoadedState = {
        kind: SessionWorkspaceTrampolineStateKinds.Loaded,
        loadedSessionWorkspace,
      };

      this._currentState = loadedState;
    } catch (e: unknown) {
      const failedState: IFailedState = {
        kind: SessionWorkspaceTrampolineStateKinds.Failed,
        error: e,

        retry: () => {
          if (this.currentState.kind !== SessionWorkspaceTrampolineStateKinds.Failed) {
            console.warn('Cannot retry loading session workspace: current state is not failed');
          }

          this._tryLoadingSessionWorkspace();
        },
      };

      this._currentState = failedState;
    }
  }

  get currentState(): USessionWorkspaceTrampolineState {
    return this._currentState;
  }
}
