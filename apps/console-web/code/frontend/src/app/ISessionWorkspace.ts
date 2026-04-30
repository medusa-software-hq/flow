import { USessionWorkspaceState } from './ISessionWorkspaceState';

export interface ISessionWorkspace {
  get currentState(): USessionWorkspaceState;

  freeze(): void;
}
