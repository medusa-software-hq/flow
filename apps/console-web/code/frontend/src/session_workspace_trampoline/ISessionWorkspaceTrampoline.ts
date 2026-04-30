import { USessionWorkspaceTrampolineState } from './ISessionWorkspaceTrampolineState';

export interface ISessionWorkspaceTrampoline {
  get sessionWorkspaceId(): string | null;

  get currentState(): USessionWorkspaceTrampolineState;
}
