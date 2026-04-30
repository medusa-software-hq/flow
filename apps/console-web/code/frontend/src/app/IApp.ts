import { ISessionWorkspaceTrampoline } from '@/session_workspace_trampoline/ISessionWorkspaceTrampoline';
import { TSessionWorkspaceTrampolineStateKind } from '@/session_workspace_trampoline/SessionWorkspaceTrampolineStateKinds';

export type TAppSessionTone = 'blue' | 'lime' | 'ink' | 'violet' | 'pink';
export type TSessionWorkspaceId = string;

export interface IAppSessionSummary {
  readonly id: TSessionWorkspaceId;
  readonly label: string;
  readonly tone: TAppSessionTone;
  readonly isSelected: boolean;
  readonly stateKind: TSessionWorkspaceTrampolineStateKind;
  readonly onSelected: () => void;
}

export interface IApp {
  get selectedSessionWorkspaceId(): TSessionWorkspaceId | null;

  get selectedSessionWorkspaceTrampoline(): ISessionWorkspaceTrampoline | null;

  get sessionWorkspaceTrampolineById(): ReadonlyMap<
    TSessionWorkspaceId,
    ISessionWorkspaceTrampoline
  >;

  get sessions(): readonly IAppSessionSummary[];

  createSessionWorkspace(): TSessionWorkspaceId;

  selectSessionWorkspace(sessionWorkspaceId: TSessionWorkspaceId): void;
}
