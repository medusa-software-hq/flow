import { create } from '@bufbuild/protobuf';
import { proxyMap } from 'valtio/utils';
import { GrpcControlServiceListFlowsRequestSchema } from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { associate } from '@/utils/mapUtils';
import { IApp, IAppLoadArgs, TSessionWorkspaceId } from './IApp';
import { CSessionWorkspaceTrampoline } from './session_workspace_trampoline/CSessionWorkspaceTrampoline';
import { ISessionWorkspaceTrampoline } from './session_workspace_trampoline/ISessionWorkspaceTrampoline';

export class CApp implements IApp {
  static async load({ coreServiceClient }: IAppLoadArgs): Promise<IApp> {
    const listFlowsResponse = await coreServiceClient.listFlows(
      create(GrpcControlServiceListFlowsRequestSchema)
    );

    const restoredSessionWorkspaceTrampolineById = associate(
      listFlowsResponse.flows,
      (flowDump) => {
        const restoredSessionWorkspaceTrampoline = CSessionWorkspaceTrampoline.restore({
          coreServiceClient,
          receivedSessionDump: flowDump,
        });

        return [flowDump.id, restoredSessionWorkspaceTrampoline];
      }
    );

    return new CApp({
      coreServiceClient,
      initialSessionWorkspaceTrampolineById: restoredSessionWorkspaceTrampolineById,
    });
  }

  private readonly _coreServiceClient: CoreServiceClient;

  private _nextSessionWorkspaceNumber = 1;
  private _selectedSessionWorkspaceId: TSessionWorkspaceId | null = null;
  private readonly _sessionWorkspaceTrampolineById: Map<
    TSessionWorkspaceId,
    ISessionWorkspaceTrampoline
  >;

  private constructor(args: {
    coreServiceClient: CoreServiceClient;
    initialSessionWorkspaceTrampolineById: Map<TSessionWorkspaceId, ISessionWorkspaceTrampoline>;
  }) {
    this._coreServiceClient = args.coreServiceClient;
    this._sessionWorkspaceTrampolineById = proxyMap(args.initialSessionWorkspaceTrampolineById);
  }

  get selectedSessionWorkspaceId(): TSessionWorkspaceId | null {
    return this._selectedSessionWorkspaceId;
  }

  get selectedSessionWorkspaceTrampoline(): ISessionWorkspaceTrampoline | null {
    const selectedSessionWorkspaceId = this.selectedSessionWorkspaceId;

    if (selectedSessionWorkspaceId === null) {
      return null;
    }

    return this._sessionWorkspaceTrampolineById.get(selectedSessionWorkspaceId) ?? null;
  }

  get sessionWorkspaceTrampolineById(): ReadonlyMap<
    TSessionWorkspaceId,
    ISessionWorkspaceTrampoline
  > {
    return this._sessionWorkspaceTrampolineById;
  }

  createSessionWorkspace(): TSessionWorkspaceId {
    const newSessionWorkspaceId = `session-workspace-${this._nextSessionWorkspaceNumber++}`;

    const newSessionWorkspaceTrampoline = CSessionWorkspaceTrampoline.createNew({
      coreServiceClient: this._coreServiceClient,
    });

    this._sessionWorkspaceTrampolineById.set(newSessionWorkspaceId, newSessionWorkspaceTrampoline);
    this._selectedSessionWorkspaceId = newSessionWorkspaceId;

    return newSessionWorkspaceId;
  }

  selectSessionWorkspace(sessionWorkspaceId: TSessionWorkspaceId): void {
    if (!this._sessionWorkspaceTrampolineById.has(sessionWorkspaceId)) {
      throw new Error(`Session workspace with ID ${sessionWorkspaceId} not found`);
    }

    this._selectedSessionWorkspaceId = sessionWorkspaceId;
  }
}
