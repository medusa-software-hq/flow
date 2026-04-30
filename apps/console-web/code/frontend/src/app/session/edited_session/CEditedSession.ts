import { proxyMap } from 'valtio/utils';
import { AppStateKinds } from '@/app/AppStateKinds';
import { IEditedSessionTrait } from '@/app/ISessionTrait';
import { CBaseSession } from '@/app/session/running_session/CBaseSession';
import type { IEditedSession } from '../ISession';
import type { ITaskPosition } from '../ITask.ts';
import { CEditedTask, type TTaskId } from './CEditedTask';

const initialTaskId = 0n;

export class CEditedSession extends CBaseSession<IEditedSessionTrait> implements IEditedSession {
  static create(): IEditedSession {
    return new CEditedSession();
  }

  readonly kind = AppStateKinds.Editing;

  private _stamp = 0;
  private _nextTaskId = 1n;

  private constructor() {
    super();
  }

  private _taskById: Map<TTaskId, CEditedTask> = proxyMap([
    [
      initialTaskId,
      new CEditedTask({
        id: initialTaskId,
        initialSourceTaskId: null,
        initialPosition: { x: 0, y: 0 },
      }),
    ],
  ]);

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
