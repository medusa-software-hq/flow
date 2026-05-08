import { create } from '@bufbuild/protobuf';
import { asyncScheduler, Subject, throttleTime } from 'rxjs';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import {
  GrpcControlServiceStartFlowRequestSchema,
  GrpcControlServiceUpdateFlowRequestSchema,
  PbFlowDetails,
  PbFlowDetailsSchema,
  PbFlowDump,
  PbFlowStartedResult,
  PbTaskExecutionProgress,
  PbTaskGraph,
  PbTaskGraphSchema,
  PbTaskSchema,
} from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { DisposableGate } from '@/utils/concurrent/DisposableGate';
import { Gate } from '@/utils/concurrent/Gate';
import { IEditedSessionTrait } from '../ISessionTrait';
import { CEditedTaskGraph } from '../task_graph/edited/CEditedTaskGraph';
import { IEditedTaskGraph } from '../task_graph/edited/IEditedTaskGraph';
import { ITaskGraph, UAnyTaskGraph } from '../task_graph/ITaskGraph';
import { CLoopSessionEditNotifier } from './CLoopSessionEditNotifier';
import { IEditingSessionWorkspaceState } from './IEditingSessionWorkspaceState';

const uploadIntervalMs = 2000;

export interface IEditingSessionWorkspaceStateTransistor {
  enterRunningState(args: { runningSessionDetails: PbFlowDetails }): void;
}

export class CEditingSessionWorkspaceState implements IEditingSessionWorkspaceState {
  static restore(args: {
    coreServiceClient: CoreServiceClient;
    receivedSessionDump: PbFlowDump;
    stateTransistor: IEditingSessionWorkspaceStateTransistor;
  }): IEditingSessionWorkspaceState {
    const loopSessionEditNotifier = new CLoopSessionEditNotifier();

    const receivedSessionDetails = args.receivedSessionDump.details;

    if (receivedSessionDetails === undefined) {
      throw new Error('Missing session details in received session dump');
    }

    const receivedTaskGraph = receivedSessionDetails.taskGraph;

    if (receivedTaskGraph === undefined) {
      throw new Error('Missing task graph in received session details');
    }

    const restoredTaskGraph = CEditedTaskGraph.restore({
      sessionEditNotifier: loopSessionEditNotifier,
      receivedTaskGraph: receivedTaskGraph,
    });

    return new CEditingSessionWorkspaceState(loopSessionEditNotifier, {
      coreServiceClient: args.coreServiceClient,
      sessionId: args.receivedSessionDump.id,
      stateTransistor: args.stateTransistor,
      initialSessionTitle: receivedSessionDetails?.title ?? '',
      editedTaskGraph: restoredTaskGraph,
    });
  }

  static createNew(args: {
    coreServiceClient: CoreServiceClient;
    sessionId: string;
    stateTransistor: IEditingSessionWorkspaceStateTransistor;
  }): IEditingSessionWorkspaceState {
    const loopSessionEditNotifier = new CLoopSessionEditNotifier();

    const newTaskGraph = CEditedTaskGraph.createNew({
      sessionEditNotifier: loopSessionEditNotifier,
    });

    return new CEditingSessionWorkspaceState(loopSessionEditNotifier, {
      coreServiceClient: args.coreServiceClient,
      sessionId: args.sessionId,
      stateTransistor: args.stateTransistor,
      initialSessionTitle: '',
      editedTaskGraph: newTaskGraph,
    });
  }

  readonly kind = SessionWorkspaceStateKinds.Editing;

  private readonly _coreServiceClient: CoreServiceClient;
  private readonly _stateTransistor: IEditingSessionWorkspaceStateTransistor;
  private readonly _sessionId: string;

  private _sessionTitle: string;

  readonly editedTaskGraph: IEditedTaskGraph;

  private readonly _onSessionEdited = new Subject<void>();

  private readonly _uploadGate: DisposableGate = Gate.liftedOnEmission(
    this._onSessionEdited.pipe(
      throttleTime(uploadIntervalMs, asyncScheduler, { leading: false, trailing: true })
    )
  );

  private constructor(
    loopSessionEditNotifier: CLoopSessionEditNotifier,
    args: {
      coreServiceClient: CoreServiceClient;
      stateTransistor: IEditingSessionWorkspaceStateTransistor;
      sessionId: string;
      initialSessionTitle: string;
      editedTaskGraph: IEditedTaskGraph;
    }
  ) {
    loopSessionEditNotifier.close(this._onSessionEdited);

    this._coreServiceClient = args.coreServiceClient;
    this._stateTransistor = args.stateTransistor;
    this._sessionId = args.sessionId;
    this._sessionTitle = args.initialSessionTitle;

    this.editedTaskGraph = args.editedTaskGraph;

    void this._syncSessionContinuously();
  }

  get exposedGenericTaskGraph(): ITaskGraph<IEditedSessionTrait> {
    return this.editedTaskGraph;
  }

  get exposedAnyTaskGraph(): UAnyTaskGraph {
    return this.editedTaskGraph;
  }

  get sessionTitle(): string {
    return this._sessionTitle;
  }

  set sessionTitle(value: string) {
    this._sessionTitle = value;
    this._onSessionEdited.next();
  }

  start(): void {
    void this._start();
  }

  check(): void {}

  [Symbol.dispose](): void {
    this._uploadGate[Symbol.dispose]();
  }

  private async _syncSessionContinuously(): Promise<void> {
    // noinspection InfiniteLoopJS
    while (true) {
      console.info('Waiting for session edits...');

      await this._uploadGate.enterThrough();

      console.info('Detected session edits, uploading session details...');

      await this._coreServiceClient.updateFlow(
        create(GrpcControlServiceUpdateFlowRequestSchema, {
          flowId: this._sessionId,
          details: dumpSession(this),
        })
      );
    }
  }

  private async _start(): Promise<void> {
    const response = await this._coreServiceClient.startFlow(
      create(GrpcControlServiceStartFlowRequestSchema, {
        flowId: this._sessionId,
        finalDetails: dumpSession(this),
      })
    );

    const result = response.result;

    switch (result.case) {
      case 'started': {
        const sessionStartedResult: PbFlowStartedResult = result.value;
        const runningSessionDetails: PbFlowDetails | undefined =
          sessionStartedResult.startedFlowDetails;

        if (runningSessionDetails === undefined) {
          throw new Error('Missing startedSessionDetails in startSession response');
        }

        this._stateTransistor.enterRunningState({
          runningSessionDetails,
        });

        break;
      }
      default: {
        throw new Error(`Unexpected startSession result case: ${result.case}`);
      }
    }
  }
}

function dumpSession(sessionEditor: IEditingSessionWorkspaceState): PbFlowDetails {
  return create(PbFlowDetailsSchema, {
    title: sessionEditor.sessionTitle,
    taskGraph: dumpTaskGraph(sessionEditor.editedTaskGraph),
  });
}

function dumpTaskGraph(editedTaskGraph: IEditedTaskGraph): PbTaskGraph {
  return create(PbTaskGraphSchema, {
    tasks: Array.from(editedTaskGraph.taskById.values(), (task) =>
      create(PbTaskSchema, {
        id: String(task.id),
        label: task.label,
        description: task.description,
        sourceTaskIds: Array.from(task.sourceTaskIds, String),
        x: task.position.x,
        y: task.position.y,
      })
    ),
  });
}
