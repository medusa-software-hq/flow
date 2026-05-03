import { proxyMap } from 'valtio/utils';
import { ISessionEditNotifier } from '@/app/session_workspace/editing/ISessionEditNotifier';
import { IEditedSessionTrait } from '@/app/session_workspace/ISessionTrait';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import { IEditedTask } from '@/app/session_workspace/task_graph/edited/IEditedTask';
import type { ITaskPosition } from '@/app/session_workspace/task_graph/ITask';
import { CBaseTaskGraph } from '@/app/session_workspace/task_graph/running/CBaseTaskGraph';
import { PbTaskGraph } from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { associate } from '@/utils/mapUtils';
import { CEditedTask, type TTaskId } from './CEditedTask';
import { IEditedTaskGraph } from './IEditedTaskGraph';

const initialTaskId = 0n;

export class CEditedTaskGraph
  extends CBaseTaskGraph<IEditedSessionTrait>
  implements IEditedTaskGraph
{
  static restore(args: {
    sessionEditNotifier: ISessionEditNotifier;
    receivedTaskGraph: PbTaskGraph;
  }): IEditedTaskGraph {
    return new CEditedTaskGraph({
      sessionEditNotifier: args.sessionEditNotifier,
      initialTaskById: associate(args.receivedTaskGraph.tasks, (receivedTask) => {
        const taskId = BigInt(receivedTask.id);

        const editedTask = CEditedTask.restore({
          sessionEditNotifier: args.sessionEditNotifier,
          receivedTask,
        });

        return [taskId, editedTask];
      }),
    });
  }

  static createNew(args: { sessionEditNotifier: ISessionEditNotifier }): IEditedTaskGraph {
    return new CEditedTaskGraph({
      sessionEditNotifier: args.sessionEditNotifier,
      initialTaskById: new Map([
        [
          initialTaskId,
          CEditedTask.createNew({
            sessionEditNotifier: args.sessionEditNotifier,
            id: initialTaskId,
            initialSourceTaskIds: new Set(),
            initialPosition: { x: 0, y: 0 },
          }),
        ],
      ]),
    });
  }

  readonly kind = SessionWorkspaceStateKinds.Editing;

  private readonly _sessionEditNotifier: ISessionEditNotifier;
  private _stamp = 0;
  private _nextTaskId: bigint;
  private readonly _taskById: Map<TTaskId, CEditedTask>;

  private constructor(args: {
    sessionEditNotifier: ISessionEditNotifier;
    initialTaskById: Map<TTaskId, CEditedTask>;
  }) {
    super();

    this._sessionEditNotifier = args.sessionEditNotifier;

    const initialTaskById = args.initialTaskById;
    const maxTaskId = Math.max(...Array.from(initialTaskById.keys(), (taskId) => Number(taskId)));

    this._taskById = proxyMap(initialTaskById);
    this._nextTaskId = BigInt(maxTaskId + 1);
  }

  get stamp(): unknown {
    return this._stamp;
  }

  get taskById(): ReadonlyMap<TTaskId, IEditedTask> {
    return this._taskById;
  }

  createDependencyTask(
    targetTaskId: TTaskId,
    newTaskPosition: ITaskPosition
  ): readonly [TTaskId, CEditedTask] {
    const targetTask = this.getTaskById(targetTaskId);

    if (targetTask === null) {
      throw new Error(`Target task with ID ${String(targetTaskId)} not found`);
    }

    const newTaskId = this._nextTaskId++;

    console.log(`Adding dependency task with ID ${String(newTaskId)}`);

    const newDependencyTask = CEditedTask.createNew({
      sessionEditNotifier: this._sessionEditNotifier,
      id: newTaskId,
      initialSourceTaskIds: new Set(),
      initialPosition: newTaskPosition,
    });

    targetTask.addSourceTask(newTaskId);

    this._taskById.set(newTaskId, newDependencyTask);

    this._finishEdit();

    return [newTaskId, newDependencyTask];
  }

  createDependentTask(
    sourceTaskId: TTaskId,
    newTaskPosition: ITaskPosition
  ): readonly [TTaskId, CEditedTask] {
    const newTaskId = this._nextTaskId++;

    const newDependentTask = CEditedTask.createNew({
      sessionEditNotifier: this._sessionEditNotifier,
      id: newTaskId,
      initialSourceTaskIds: new Set([sourceTaskId]),
      initialPosition: newTaskPosition,
    });

    console.log(`Adding dependent task with ID ${String(newTaskId)}`);

    this._taskById.set(newTaskId, newDependentTask);

    this._finishEdit();

    return [newTaskId, newDependentTask];
  }

  createDependency(sourceTaskId: TTaskId, targetTaskId: TTaskId) {
    const sourceTask = this._taskById.get(sourceTaskId);

    if (sourceTask === undefined) {
      throw new Error(`Source task with ID ${String(sourceTaskId)} not found`);
    }

    const targetTask = this._taskById.get(targetTaskId);

    if (targetTask === undefined) {
      throw new Error(`Target task with ID ${String(targetTaskId)} not found`);
    }

    if (targetTask.sourceTaskIds.has(sourceTaskId)) {
      throw new Error(
        `Source task ${String(sourceTaskId)} is already a dependency of task ${String(targetTaskId)}`
      );
    }

    console.log(
      `Creating dependency from task ${String(sourceTaskId)} to task ${String(targetTaskId)}`
    );

    targetTask.sourceTaskIds.add(sourceTaskId);

    this._finishEdit();
  }

  deleteTask(taskId: TTaskId) {
    const deletedTask = this.getTaskById(taskId);

    if (deletedTask === null) {
      throw new Error(`Task with ID ${String(taskId)} not found`);
    }

    console.log(`Deleting task with ID ${String(taskId)}`);

    for (const potentialTargetTask of this._taskById.values()) {
      potentialTargetTask.sourceTaskIds.delete(taskId);
    }

    this._taskById.delete(taskId);

    this._finishEdit();
  }

  breakDependency(sourceTaskId: TTaskId, targetTaskId: TTaskId) {
    const sourceTask = this._taskById.get(sourceTaskId);

    if (sourceTask === undefined) {
      throw new Error(`Source task with ID ${String(sourceTaskId)} not found`);
    }

    const targetTask = this._taskById.get(targetTaskId);

    if (targetTask === undefined) {
      throw new Error(`Target task with ID ${String(targetTaskId)} not found`);
    }

    console.log(
      `Breaking dependency from task ${String(sourceTaskId)} to task ${String(targetTaskId)}`
    );

    targetTask.sourceTaskIds.delete(sourceTaskId);

    this._finishEdit();
  }

  private _finishEdit() {
    ++this._stamp;

    this._sessionEditNotifier.notifyEdited();
  }
}
