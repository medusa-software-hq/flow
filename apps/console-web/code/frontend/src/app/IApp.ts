import { ISessionWorkspaceTrampoline } from '@/session_workspace_trampoline/ISessionWorkspaceTrampoline';
import { TSessionWorkspaceTrampolineStateKind } from '@/session_workspace_trampoline/SessionWorkspaceTrampolineStateKinds';

export type TSessionWorkspaceId = string;

export interface IAppSessionSummary {
  readonly id: TSessionWorkspaceId;
  readonly title: string;
  readonly isSelected: boolean;
  readonly stateKind: TSessionWorkspaceTrampolineStateKind;
  readonly onSelected: () => void;
}

export interface IAppLoadArgs {
  readonly coreServiceClient: import('@/rpc/myGrpcTypes').CoreServiceClient;
}

export interface IApp {
  get selectedSessionWorkspaceId(): TSessionWorkspaceId | null;

  get selectedSessionWorkspaceTrampoline(): ISessionWorkspaceTrampoline | null;

  get selectedSessionTitle(): string | null;

  get sessionWorkspaceTrampolineById(): ReadonlyMap<
    TSessionWorkspaceId,
    ISessionWorkspaceTrampoline
  >;

  get sessions(): readonly IAppSessionSummary[];

  createSessionWorkspace(): TSessionWorkspaceId;

  selectSessionWorkspace(sessionWorkspaceId: TSessionWorkspaceId): void;

  setSessionTitle(sessionWorkspaceId: TSessionWorkspaceId, title: string): void;
}
