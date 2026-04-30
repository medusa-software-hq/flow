import { USessionWorkspaceTrampolineState } from './ISessionWorkspaceTrampolineState';

export interface ISessionWorkspaceTrampoline {
  get currentState(): USessionWorkspaceTrampolineState;
}
