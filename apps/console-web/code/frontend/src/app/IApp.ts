import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { ISessionWorkspaceTrampoline } from './session_workspace_trampoline/ISessionWorkspaceTrampoline';

export type TSessionWorkspaceId = string;

export interface IAppLoadArgs {
  readonly coreServiceClient: CoreServiceClient;
}

export interface IApp {
  get selectedSessionWorkspaceId(): TSessionWorkspaceId | null;

  get selectedSessionWorkspaceTrampoline(): ISessionWorkspaceTrampoline | null;

  get sessionWorkspaceTrampolineById(): ReadonlyMap<
    TSessionWorkspaceId,
    ISessionWorkspaceTrampoline
  >;

  createSessionWorkspace(): TSessionWorkspaceId;

  selectSessionWorkspace(sessionWorkspaceId: TSessionWorkspaceId): void;
}
