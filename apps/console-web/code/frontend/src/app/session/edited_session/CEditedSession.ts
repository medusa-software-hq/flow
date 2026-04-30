import { proxyMap } from 'valtio/utils';
import { IEditedSessionTrait } from '@/app/ISessionTrait';
import { CBaseSession } from '@/app/session/running_session/CBaseSession';
import { SessionWorkspaceStateKinds } from '@/app/SessionWorkspaceStateKinds';
import { TaskGraph } from '@/gen/medusa/flow/core_service/v1/core_service_pb';
import type { IEditedSession } from '../ISession';
import type { ITaskPosition } from '../ITask.ts';
import { CEditedTask, type TTaskId } from './CEditedTask';

const initialTaskId = 0n;

export class CEditedSession extends CBaseSession<IEditedSessionTrait> implements IEditedSession {
  static createNew(): IEditedSession {
    return new CEditedSession({
      initialTaskById: createInitialTaskById(),
    });
  }

  static restore(taskGraph: TaskGraph | null): IEditedSession {
    return new CEditedSession({
      initialTaskById: restoreTaskById(taskGraph),
    });
  }

  readonly kind = SessionWorkspaceStateKinds.Editing;

  private _stamp = 0;
  private _nextTaskId: bigint;

  private constructor(args?: { initialTaskById?: Map<TTaskId, CEditedTask> }) {
    super();

    const initialTaskById = args?.initialTaskById ?? createInitialTaskById();

    this._taskById = proxyMap(initialTaskById);

    const maxTaskId = Math.max(...Array.from(initialTaskById.keys(), (taskId) => Number(taskId)));
    this._nextTaskId = BigInt(maxTaskId + 1);
  }

  private _taskById: Map<TTaskId, CEditedTask>;

  get stamp(): unknown {
    return this._stamp;
  }

  get taskById(): ReadonlyMap<TTaskId, CEditedTask> {
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

    const newDependencyTask = new CEditedTask({
      id: newTaskId,
      initialSourceTaskId: null,
      initialPosition: newTaskPosition,
    });

    targetTask.addSourceTask(newTaskId);

    this._taskById.set(newTaskId, newDependencyTask);

    return [newTaskId, newDependencyTask];
  }

  createDependentTask(
    sourceTaskId: TTaskId,
    newTaskPosition: ITaskPosition
  ): readonly [TTaskId, CEditedTask] {
    const newTaskId = this._nextTaskId++;

    const newDependentTask = new CEditedTask({
      id: newTaskId,
      initialSourceTaskId: sourceTaskId,
      initialPosition: newTaskPosition,
    });

    console.log(`Adding dependent task with ID ${String(newTaskId)}`);

    this._taskById.set(newTaskId, newDependentTask);

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

    this._doStamp();
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

    this._doStamp();
  }

  private _doStamp() {
    ++this._stamp;
  }
}

function createInitialTaskById(): Map<TTaskId, CEditedTask> {
  return new Map([
    [
      initialTaskId,
      new CEditedTask({
        id: initialTaskId,
        initialSourceTaskId: null,
        initialPosition: { x: 0, y: 0 },
      }),
    ],
  ]);
}

function restoreTaskById(taskGraph: TaskGraph | null): Map<TTaskId, CEditedTask> {
  if (taskGraph === null || taskGraph.tasks.length === 0) {
    return createInitialTaskById();
  }

  return new Map(
    taskGraph.tasks.map((task) => {
      const taskId = BigInt(task.id);
      const editedTask = new CEditedTask({
        id: taskId,
        initialSourceTaskIds: new Set(
          task.sourceTaskIds.map((sourceTaskId) => BigInt(sourceTaskId))
        ),
        initialPosition: { x: task.x, y: task.y },
        initialLabel: task.label,
        initialDescription: task.description,
      });

      return [taskId, editedTask] as const;
    })
  );
}
