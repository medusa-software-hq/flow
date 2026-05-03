import { proxyMap } from 'valtio/utils';
import { IRunningSessionTrait } from '@/app/session_workspace/ISessionTrait';
import { IRunningSessionObserver } from '@/app/session_workspace/running/observer/IRunningSessionObserver';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import { PbTask, PbTaskGraph } from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { associate } from '@/utils/mapUtils';
import { TTaskId } from '../edited/CEditedTask';
import { CBaseTaskGraph } from './CBaseTaskGraph';
import { CRunningTask } from './CEditedTask';
import { IRunningTaskGraph } from './IRunningTaskGraph';

export class CRunningTaskGraph
  extends CBaseTaskGraph<IRunningSessionTrait>
  implements IRunningTaskGraph
{
  static preview(args: {
    baseTaskGraph: PbTaskGraph | undefined;
    runningSessionObserver: IRunningSessionObserver;
  }): IRunningTaskGraph {
    const runningTaskById = associate(args.baseTaskGraph?.tasks ?? [], (baseTask: PbTask) => {
      const runningTask = CRunningTask.preview({
        baseTask,
        runningSessionObserver: args.runningSessionObserver,
      });

      return [runningTask.id, runningTask];
    });

    return new CRunningTaskGraph({
      runningTaskById,
    });
  }

  readonly kind = SessionWorkspaceStateKinds.Running;

  private constructor(args: { runningTaskById: ReadonlyMap<TTaskId, CRunningTask> }) {
    super();

    this.taskById = proxyMap(new Map(args.runningTaskById));
  }

  readonly taskById: ReadonlyMap<TTaskId, CRunningTask>;

  get stamp(): unknown {
    return 0;
  }

  getSessionProgress(): number {
    const tasks = Array.from(this.taskById.values());

    if (tasks.length === 0) {
      return 0;
    }

    return tasks.reduce((acc, task) => acc + task.getProgress(), 0) / tasks.length;
  }
}
