import { IRunningSessionTrait } from '@/app/session_workspace/ISessionTrait';
import { ITaskGraph, UAnyTaskGraph } from '@/app/session_workspace/task_graph/ITaskGraph';
import { CRunningTaskGraph } from '@/app/session_workspace/task_graph/running/CRunningTaskGraph';
import { IRunningTaskGraph } from '@/app/session_workspace/task_graph/running/IRunningTaskGraph';
import { PbSessionDetails } from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { SessionWorkspaceStateKinds } from '../SessionWorkspaceStateKinds';
import { IRunningSessionWorkspaceState } from './IRunningSessionWorkspaceState';
import { CRunningSessionObserver } from './observer/CRunningSessionObserver';
import { IRunningSessionObserver } from './observer/IRunningSessionObserver';

export class CRunningSessionWorkspaceState implements IRunningSessionWorkspaceState {
  static observe(args: {
    coreServiceClient: CoreServiceClient;
    sessionId: string;
    baseSessionDetails: PbSessionDetails;
  }) {
    const runningSessionObserver: IRunningSessionObserver = CRunningSessionObserver.observe({
      coreServiceClient: args.coreServiceClient,
      sessionId: args.sessionId,
    });

    return new CRunningSessionWorkspaceState({
      runningTaskGraph: CRunningTaskGraph.preview({
        baseTaskGraph: args.baseSessionDetails?.taskGraph,
        runningSessionObserver,
      }),
      sessionTitle: args.baseSessionDetails?.title ?? '',
    });
  }

  readonly kind = SessionWorkspaceStateKinds.Running;

  readonly runningTaskGraph: IRunningTaskGraph;

  readonly sessionTitle: string;

  private constructor(args: { runningTaskGraph: IRunningTaskGraph; sessionTitle: string }) {
    this.runningTaskGraph = args.runningTaskGraph;
    this.sessionTitle = args.sessionTitle;
  }

  get exposedGenericTaskGraph(): ITaskGraph<IRunningSessionTrait> {
    return this.runningTaskGraph;
  }

  get exposedAnyTaskGraph(): UAnyTaskGraph {
    return this.runningTaskGraph;
  }

  [Symbol.dispose](): void {
    throw new Error('Method not implemented.');
  }
}

// TODO: loadSession / loadTaskGraph, inverse of dumpSession / dumpTaskGraph but for IRunningTaskGraph
