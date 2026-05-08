import { create } from '@bufbuild/protobuf';
import { proxy } from 'valtio';
import {
  GrpcControlServiceCreateFlowRequestSchema,
  PbFlowDump,
} from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { CSessionWorkspace } from '../session_workspace/CSessionWorkspace';
import { ISessionWorkspaceTrampoline } from './ISessionWorkspaceTrampoline';
import {
  ICreatingState,
  ICreationFailedState,
  IOperationalState,
  USessionWorkspaceTrampolineState,
} from './ISessionWorkspaceTrampolineState';
import { SessionWorkspaceTrampolineStateKinds } from './SessionWorkspaceTrampolineStateKinds';

export class CSessionWorkspaceTrampoline implements ISessionWorkspaceTrampoline {
  static restore(args: {
    coreServiceClient: CoreServiceClient;
    receivedSessionDump: PbFlowDump;
  }): ISessionWorkspaceTrampoline {
    const restoredState: IOperationalState = {
      kind: SessionWorkspaceTrampolineStateKinds.Operational,
      operationalSessionWorkspace: CSessionWorkspace.restore(args),
    };

    return new CSessionWorkspaceTrampoline({
      coreServiceClient: args.coreServiceClient,
      sessionWorkspaceId: '!',
      initialState: restoredState,
    });
  }

  static createNew(args: { coreServiceClient: CoreServiceClient }): ISessionWorkspaceTrampoline {
    const initialState: ICreatingState = {
      kind: SessionWorkspaceTrampolineStateKinds.Creating,
    };

    const self: CSessionWorkspaceTrampoline = proxy(
      new CSessionWorkspaceTrampoline({
        coreServiceClient: args.coreServiceClient,
        sessionWorkspaceId: '!',
        initialState: initialState,
      })
    );

    void self._tryCreatingSessionWorkspace();

    return self;
  }

  private readonly _coreServiceClient: CoreServiceClient;
  private readonly _sessionWorkspaceId: string;

  private _currentState: USessionWorkspaceTrampolineState;

  private constructor(args: {
    coreServiceClient: CoreServiceClient;
    sessionWorkspaceId: string;
    initialState: USessionWorkspaceTrampolineState;
  }) {
    this._coreServiceClient = args.coreServiceClient;
    this._sessionWorkspaceId = args.sessionWorkspaceId;
    this._currentState = args.initialState;
  }

  private async _tryCreatingSessionWorkspace() {
    const loadingState: ICreatingState = {
      kind: SessionWorkspaceTrampolineStateKinds.Creating,
    };

    this._currentState = loadingState;

    try {
      const response = await this._coreServiceClient.createFlow(
        create(GrpcControlServiceCreateFlowRequestSchema)
      );

      const createdSessionWorkspace = CSessionWorkspace.createNew({
        coreServiceClient: this._coreServiceClient,
        sessionId: response.flowId,
      });

      const loadedState: IOperationalState = {
        kind: SessionWorkspaceTrampolineStateKinds.Operational,
        operationalSessionWorkspace: createdSessionWorkspace,
      };

      this._currentState = loadedState;
    } catch (e: unknown) {
      const failedState: ICreationFailedState = {
        kind: SessionWorkspaceTrampolineStateKinds.Failed,
        error: e,

        retry: () => {
          if (this.currentState.kind !== SessionWorkspaceTrampolineStateKinds.Failed) {
            console.warn('Cannot retry loading session workspace: current state is not failed');
          } else {
            this._tryCreatingSessionWorkspace();
          }
        },
      };

      this._currentState = failedState;
    }
  }

  get sessionWorkspaceId(): string | null {
    return this._sessionWorkspaceId;
  }

  get currentState(): USessionWorkspaceTrampolineState {
    return this._currentState;
  }
}
