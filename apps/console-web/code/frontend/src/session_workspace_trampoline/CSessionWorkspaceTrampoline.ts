import { create } from '@bufbuild/protobuf';
import { proxy } from 'valtio';
import { CSessionWorkspace } from '@/app/CSessionWorkspace';
import {
  SessionSummary,
  StartSessionRequestSchema,
  TaskGraph,
} from '@/gen/medusa/flow/core_service/v1/core_service_pb';
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
    restoredSessionSummary?: SessionSummary;
    initialTaskGraph?: TaskGraph;
  }): ISessionWorkspaceTrampoline {
    const self: CSessionWorkspaceTrampoline = proxy(new CSessionWorkspaceTrampoline(args));

    self._initialize();

    return self;
  }

  private readonly _coreServiceClient: CoreServiceClient;
  private readonly _restoredSessionSummary: SessionSummary | null;
  private readonly _initialTaskGraph: TaskGraph | null;
  private _sessionWorkspaceId: string | null;

  private _currentState: USessionWorkspaceTrampolineState = {
    kind: SessionWorkspaceTrampolineStateKinds.Loading,
  };

  private constructor(args: {
    coreServiceClient: CoreServiceClient;
    restoredSessionSummary?: SessionSummary;
    initialTaskGraph?: TaskGraph;
  }) {
    this._coreServiceClient = args.coreServiceClient;
    this._restoredSessionSummary = args.restoredSessionSummary ?? null;
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
          create(StartSessionRequestSchema, {
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
          ? await CSessionWorkspace.createNew(this._coreServiceClient, this._sessionWorkspaceId!)
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
