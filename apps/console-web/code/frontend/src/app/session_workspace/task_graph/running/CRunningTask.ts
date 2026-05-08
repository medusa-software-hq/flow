import {
  TaskDefinitionUtils,
  UTaskDefinition,
} from '@/app/session_workspace/task_graph/ITaskDefinition';
import { PbTask } from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { IRunningSessionObserver } from '../../running/observer/IRunningSessionObserver';
import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';
import { TTaskId } from '../edited/CEditedTask';
import { ITaskPosition } from '../ITask';
import { IRunningTask } from './IRunningTask';

export class CRunningTask implements IRunningTask {
  static preview(args: {
    baseTask: PbTask;
    runningSessionObserver: IRunningSessionObserver;
  }): CRunningTask {
    return new CRunningTask(args);
  }

  readonly kind = SessionWorkspaceStateKinds.Running;

  readonly _runningSessionObserver: IRunningSessionObserver;

  readonly id: TTaskId;
  readonly definition: UTaskDefinition;
  readonly position: ITaskPosition;
  readonly sourceTaskIds: ReadonlySet<TTaskId>;

  private constructor(args: { baseTask: PbTask; runningSessionObserver: IRunningSessionObserver }) {
    this._runningSessionObserver = args.runningSessionObserver;

    this.id = BigInt(args.baseTask.id);

    this.sourceTaskIds = new Set(
      args.baseTask.sourceTaskIds.map((sourceTaskId) => BigInt(sourceTaskId))
    );

    this.definition = TaskDefinitionUtils.load(args.baseTask.definition);
    this.position = { x: args.baseTask.x, y: args.baseTask.y };
  }

  getProgress(): number {
    const lastReceivedSnapshot = this._runningSessionObserver.lastReceivedSnapshot;

    if (lastReceivedSnapshot === null) {
      return 0;
    }

    return lastReceivedSnapshot.progressByTaskId.get(this.id.toString()) ?? 0;
  }
}
