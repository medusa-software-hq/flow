import { USessionWorkspaceState } from './ISessionWorkspaceState';

export interface ISessionWorkspace {
  get currentState(): USessionWorkspaceState;

  setTitle(title: string): void;

  upload(): Promise<void>;

  freeze(): void;
}
