import { IEditedSessionTrait } from '../../ISessionTrait';
import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';
import type { ITaskPosition } from '../ITask';
import { ITaskGraph } from '../ITaskGraph';
import { CEditedTask, TTaskId } from './CEditedTask';

export interface IEditedTaskGraph extends ITaskGraph<IEditedSessionTrait> {
  readonly kind: typeof SessionWorkspaceStateKinds.Editing;

  createDependencyTask(
    targetTaskId: TTaskId,
    newTaskPosition: ITaskPosition
  ): readonly [TTaskId, CEditedTask];

  createDependentTask(
    sourceTaskId: TTaskId,
    newTaskPosition: ITaskPosition
  ): readonly [TTaskId, CEditedTask];

  createDependency(sourceTaskId: TTaskId, targetTaskId: TTaskId): void;

  deleteTask(taskId: TTaskId): void;

  breakDependency(sourceTaskId: TTaskId, targetTaskId: TTaskId): void;
}
