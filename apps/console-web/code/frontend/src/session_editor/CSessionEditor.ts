import { proxyMap } from 'valtio/utils';
import { CTask, type TTaskId } from './CTask';
import type { ISessionEditor } from './ISessionEditor';
import type { ITaskPosition } from './ITask.ts';

export class CSessionEditor implements ISessionEditor {
  private _stamp = 0;
  private _nextTaskId = 1n;

  private _taskById: Map<TTaskId, CTask> = proxyMap([
    [
      0n,
      new CTask({
        initialSourceTaskId: null,
        initialPosition: { x: 0, y: 0 },
      }),
    ],
  ]);

  get stamp(): unknown {
    return this._stamp;
  }

  get taskById(): ReadonlyMap<TTaskId, CTask> {
    return this._taskById;
  }

  getTaskById(taskId: TTaskId): CTask | null {
    return this._taskById.get(taskId) ?? null;
  }

  getTargetTasks(sourceTaskId: TTaskId): ReadonlySet<CTask> {
    const sourceTask = this.getTaskById(sourceTaskId);

    if (sourceTask === null) {
      throw new Error(`Source task with ID ${String(sourceTaskId)} not found`);
    }

    return new Set(
      [...this._taskById.values()].filter((task) =>
        task._sourceTaskIds.has(sourceTaskId),
      ),
    );
  }

  createDependencyTask(targetTaskId: TTaskId, newTaskPosition: ITaskPosition) {
    const targetTask = this.getTaskById(targetTaskId);

    if (targetTask === null) {
      throw new Error(`Target task with ID ${String(targetTaskId)} not found`);
    }

    const newTaskId = this._nextTaskId++;

    console.log(`Adding dependency task with ID ${String(newTaskId)}`);

    const newDependencyTask = new CTask({
      initialSourceTaskId: null,
      initialPosition: newTaskPosition,
    });

    targetTask._sourceTaskIds.add(newTaskId);

    this._taskById.set(newTaskId, newDependencyTask);
  }

  createDependentTask(sourceTaskId: TTaskId, newTaskPosition: ITaskPosition) {
    const newTaskId = this._nextTaskId++;

    const newDependentTask = new CTask({
      initialSourceTaskId: sourceTaskId,
      initialPosition: newTaskPosition,
    });

    console.log(`Adding dependent task with ID ${String(newTaskId)}`);

    this._taskById.set(newTaskId, newDependentTask);
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

    if (targetTask._sourceTaskIds.has(sourceTaskId)) {
      throw new Error(
        `Source task ${String(sourceTaskId)} is already a dependency of task ${String(targetTaskId)}`,
      );
    }

    console.log(
      `Creating dependency from task ${String(sourceTaskId)} to task ${String(targetTaskId)}`,
    );

    targetTask._sourceTaskIds.add(sourceTaskId);

    this._doStamp();
  }

  private _doStamp() {
    ++this._stamp;
  }

  deleteTask(taskId: TTaskId) {
    const deletedTask = this.getTaskById(taskId);

    if (deletedTask === null) {
      throw new Error(`Task with ID ${String(taskId)} not found`);
    }

    console.log(`Deleting task with ID ${String(taskId)}`);

    for (const potentialTargetTask of this._taskById.values()) {
      potentialTargetTask._sourceTaskIds.delete(taskId);
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
      `Breaking dependency from task ${String(sourceTaskId)} to task ${String(targetTaskId)}`,
    );

    targetTask._sourceTaskIds.delete(sourceTaskId);

    this._doStamp();
  }
}
