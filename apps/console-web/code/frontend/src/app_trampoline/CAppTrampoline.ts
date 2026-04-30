import { proxy } from 'valtio';
import { CApp } from '@/app/CApp';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import {
  IFailedState,
  ILoadedState,
  ILoadingState,
  UAppTrampolineState,
} from '@/app_trampoline/IAppTrampolineState';

export class CAppTrampoline implements IAppTrampoline {
  static createProxied(): IAppTrampoline {
    // We need to proxy early...
    const self: CAppTrampoline = proxy(new CAppTrampoline());

    // ...to ensure that initialization goes through a proxy
    self._initialize();

    return self;
  }

  private _currentState: UAppTrampolineState = {
    kind: AppTrampolineStateKinds.Loading,
  };

  private _initialize() {
    void this._tryLoadingApp();
  }

  private async _tryLoadingApp() {
    const loadingState: ILoadingState = {
      kind: AppTrampolineStateKinds.Loading,
    };

    this._currentState = loadingState;

    try {
      console.log('Trying to load app...');

      const loadedApp = await CApp.load();

      console.log('App loaded successfully!');

      const loadedState: ILoadedState = {
        kind: AppTrampolineStateKinds.Loaded,
        loadedApp,
      };

      this._currentState = loadedState;
    } catch (e: unknown) {
      console.error('Failed to load app:', e);

      const failedState: IFailedState = {
        kind: AppTrampolineStateKinds.Failed,
        error: e,

        retry: () => {
          if (this.currentState.kind !== AppTrampolineStateKinds.Failed) {
            console.warn('Cannot retry loading app: current state is not failed');
          }

          this._tryLoadingApp();
        },
      };

      this._currentState = failedState;
    }
  }

  get currentState(): UAppTrampolineState {
    return this._currentState;
  }
}
