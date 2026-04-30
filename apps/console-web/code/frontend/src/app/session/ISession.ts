import { AppStateKinds, TAppStateKind } from '@/app/AppStateKinds';
import { IEditedSessionTrait, IRunningSessionTrait, ISessionTrait } from '../ISessionTrait';
import { CEditedTask, type TTaskId } from './edited_session/CEditedTask';
import type { ITaskPosition } from './ITask';

export type TAnySession = ISession<ISessionTrait>;

export interface ISession<$T extends ISessionTrait> {
  readonly kind: TAppStateKind;

  get stamp(): unknown;

  get taskById(): ReadonlyMap<TTaskId, $T['taskT']>;

  getTaskById(taskId: TTaskId): $T['taskT'] | null;

  getSourceTasks(sourceTaskId: TTaskId): ReadonlySet<$T['taskT']>;

  getTargetTasks(sourceTaskId: TTaskId): ReadonlySet<$T['taskT']>;
}

export interface IEditedSession extends ISession<IEditedSessionTrait> {
  readonly kind: typeof AppStateKinds.Editing;

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

export interface IRunningSession extends ISession<IRunningSessionTrait> {
  readonly kind: typeof AppStateKinds.Running;

  getSessionProgress(): number;
}

export type UAnySession = IEditedSession | IRunningSession;
