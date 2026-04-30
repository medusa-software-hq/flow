import { USessionWorkspaceState } from './ISessionWorkspaceState';

export interface ISessionWorkspace {
  get currentState(): USessionWorkspaceState;

  upload(): Promise<void>;

  freeze(): void;
}
