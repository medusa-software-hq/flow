import { UAppTrampolineState } from './IAppTrampolineState';

export interface IAppTrampoline {
  get currentState(): UAppTrampolineState;
}
