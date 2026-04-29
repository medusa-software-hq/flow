import { CTask, type TTaskId } from './CTask';

export interface ISessionEditor {
  get stamp(): unknown;

  get taskById(): ReadonlyMap<TTaskId, CTask>;

  getTaskById(taskId: TTaskId): CTask | null;

  getTargetTasks(sourceTaskId: TTaskId): ReadonlySet<CTask>;
}
