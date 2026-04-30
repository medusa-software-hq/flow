import { create } from '@bufbuild/protobuf';
import { proxy } from 'valtio';
import { CSessionWorkspace } from '@/app/CSessionWorkspace';
import {
  GrpcControlServiceStartSessionRequestSchema,
  PbSessionSummary,
  PbTaskGraph,
} from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { ISessionWorkspaceTrampoline } from './ISessionWorkspaceTrampoline';
import {
  IFailedState,
  ILoadedState,
  ILoadingState,
  USessionWorkspaceTrampolineState,
} from './ISessionWorkspaceTrampolineState';
import { SessionWorkspaceTrampolineStateKinds } from './SessionWorkspaceTrampolineStateKinds';

export class CSessionWorkspaceTrampoline implements ISessionWorkspaceTrampoline {
  static createProxied(args: {
    coreServiceClient: CoreServiceClient;
    restoredSessionSummary?: PbSessionSummary;
    initialTitle?: string;
    initialTaskGraph?: PbTaskGraph;
  }): ISessionWorkspaceTrampoline {
    const self: CSessionWorkspaceTrampoline = proxy(new CSessionWorkspaceTrampoline(args));

    self._initialize();

    return self;
  }

  private readonly _coreServiceClient: CoreServiceClient;
  private readonly _restoredSessionSummary: PbSessionSummary | null;
  private readonly _initialTitle: string;
  private readonly _initialTaskGraph: PbTaskGraph | null;
  private _sessionWorkspaceId: string | null;

  private _currentState: USessionWorkspaceTrampolineState = {
    kind: SessionWorkspaceTrampolineStateKinds.Loading,
  };

  private constructor(args: {
    coreServiceClient: CoreServiceClient;
    restoredSessionSummary?: PbSessionSummary;
    initialTitle?: string;
    initialTaskGraph?: PbTaskGraph;
  }) {
    this._coreServiceClient = args.coreServiceClient;
    this._restoredSessionSummary = args.restoredSessionSummary ?? null;
    this._initialTitle = args.initialTitle ?? '';
    this._initialTaskGraph = args.initialTaskGraph ?? null;
    this._sessionWorkspaceId = this._restoredSessionSummary?.sessionId ?? null;
  }

  private _initialize() {
    void this._tryLoadingSessionWorkspace();
  }

  private async _tryLoadingSessionWorkspace() {
    const loadingState: ILoadingState = {
      kind: SessionWorkspaceTrampolineStateKinds.Loading,
    };

    this._currentState = loadingState;

    try {
      if (this._restoredSessionSummary === null) {
        if (this._initialTaskGraph === null) {
          throw new Error('Initial task graph is required to create a new session workspace');
        }

        const startSessionResponse = await this._coreServiceClient.startSession(
          create(GrpcControlServiceStartSessionRequestSchema, {
            title: this._initialTitle,
            taskGraph: this._initialTaskGraph,
          })
        );

        if (startSessionResponse.result.case !== 'started') {
          throw new Error('Session creation failed');
        }

        this._sessionWorkspaceId = startSessionResponse.result.value.sessionId;
      }

      const loadedSessionWorkspace =
        this._restoredSessionSummary === null
          ? await CSessionWorkspace.createNew(
              this._coreServiceClient,
              this._sessionWorkspaceId!,
              this._initialTitle
            )
          : CSessionWorkspace.restore(this._coreServiceClient, this._restoredSessionSummary);

      const loadedState: ILoadedState = {
        kind: SessionWorkspaceTrampolineStateKinds.Loaded,
        loadedSessionWorkspace,
      };

      this._currentState = loadedState;
    } catch (e: unknown) {
      const failedState: IFailedState = {
        kind: SessionWorkspaceTrampolineStateKinds.Failed,
        error: e,

        retry: () => {
          if (this.currentState.kind !== SessionWorkspaceTrampolineStateKinds.Failed) {
            console.warn('Cannot retry loading session workspace: current state is not failed');
          }

          this._tryLoadingSessionWorkspace();
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
