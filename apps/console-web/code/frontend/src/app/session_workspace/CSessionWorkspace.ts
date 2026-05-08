import { USessionWorkspaceState } from '@/app/session_workspace/ISessionWorkspaceState';
import { CRunningSessionWorkspaceState } from '@/app/session_workspace/running/CRunningSessionWorkspaceState';
import {
  PbFlowDetails,
  PbFlowDump,
} from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { DisposableField } from '@/utils/DisposableField';
import { Lazy } from '@/utils/Lazy';
import { Loop } from '@/utils/Loop';
import {
  CEditingSessionWorkspaceState,
  IEditingSessionWorkspaceStateTransistor,
} from './editing/CEditingSessionWorkspaceState';
import { ISessionWorkspace } from './ISessionWorkspace';

export class CSessionWorkspace implements ISessionWorkspace {
  static restore(args: {
    coreServiceClient: CoreServiceClient;
    receivedSessionDump: PbFlowDump;
  }): ISessionWorkspace {
    const selfLoop = new Loop<CSessionWorkspace>();

    const sessionState = args.receivedSessionDump.state;

    if (sessionState === undefined) {
      throw new Error('Missing state in received session dump');
    }

    const restoreSessionState = () => {
      switch (sessionState.state.case) {
        case 'draft': {
          return CEditingSessionWorkspaceState.restore({
            coreServiceClient: args.coreServiceClient,
            receivedSessionDump: args.receivedSessionDump,
            stateTransistor: CSessionWorkspace._buildEditingStateTransistor(selfLoop),
          });
        }

        case 'running': {
          const baseSessionDetails = args.receivedSessionDump.details;

          if (baseSessionDetails === undefined) {
            throw new Error('Missing session details in received session dump');
          }

          return CRunningSessionWorkspaceState.observe({
            coreServiceClient: args.coreServiceClient,
            sessionId: args.receivedSessionDump.id,
            baseSessionDetails: baseSessionDetails,
          });
        }

        default: {
          throw new Error(`Unsupported session state: ${sessionState.state.case}`);
        }
      }
    };

    const restoredState = restoreSessionState();

    return new CSessionWorkspace(selfLoop, {
      coreServiceClient: args.coreServiceClient,
      sessionId: args.receivedSessionDump.id,
      initialState: restoredState,
    });
  }

  static createNew(args: {
    coreServiceClient: CoreServiceClient;
    sessionId: string;
  }): ISessionWorkspace {
    const selfLoop = new Loop<CSessionWorkspace>();

    const newEditingSessionWorkspaceState = CEditingSessionWorkspaceState.createNew({
      coreServiceClient: args.coreServiceClient,
      sessionId: args.sessionId,
      stateTransistor: CSessionWorkspace._buildEditingStateTransistor(selfLoop),
    });

    return new CSessionWorkspace(selfLoop, {
      coreServiceClient: args.coreServiceClient,
      sessionId: args.sessionId,
      initialState: newEditingSessionWorkspaceState,
    });
  }

  private static _buildEditingStateTransistor(
    sessionWorkspaceLazy: Lazy<CSessionWorkspace>
  ): IEditingSessionWorkspaceStateTransistor {
    return {
      enterRunningState(args: { runningSessionDetails: PbFlowDetails }) {
        const sessionWorkspace = sessionWorkspaceLazy.value;

        const newState = CRunningSessionWorkspaceState.observe({
          coreServiceClient: sessionWorkspace._coreServiceClient,
          sessionId: sessionWorkspace._sessionId,
          baseSessionDetails: args.runningSessionDetails,
        });

        sessionWorkspace._currentStateField.set(newState);
      },
    };
  }

  private readonly _coreServiceClient: CoreServiceClient;
  private readonly _sessionId: string;

  private _currentStateField: DisposableField<USessionWorkspaceState>;

  private constructor(
    selfLoop: Loop<CSessionWorkspace>,
    args: {
      coreServiceClient: CoreServiceClient;
      sessionId: string;
      initialState: USessionWorkspaceState;
    }
  ) {
    selfLoop.close(this);

    this._coreServiceClient = args.coreServiceClient;
    this._sessionId = args.sessionId;
    this._currentStateField = new DisposableField(args.initialState);
  }

  get currentState(): USessionWorkspaceState {
    return this._currentStateField.get();
  }
}
