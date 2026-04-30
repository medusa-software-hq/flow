import { USessionWorkspaceState } from './ISessionWorkspaceState';

export type TSessionWorkspaceTone = 'blue' | 'lime' | 'ink' | 'violet' | 'pink';

export interface ISessionWorkspaceSummary {
  readonly id: string;
  readonly label: string;
  readonly tone: TSessionWorkspaceTone;
  readonly isSelected: boolean;
}

export interface ISessionWorkspace {
  get currentState(): USessionWorkspaceState;

  get sessions(): readonly ISessionWorkspaceSummary[];

  freeze(): void;
}
