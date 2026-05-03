import { TSessionWorkspaceStateKind } from '../SessionWorkspaceStateKinds';
import { TTaskId } from './edited/CEditedTask';
import { IEditedTask } from './edited/IEditedTask';
import { IRunningTask } from './running/IRunningTask';

export interface ITaskPosition {
  readonly x: number;
  readonly y: number;
}

export interface ITask {
  get kind(): TSessionWorkspaceStateKind;

  get id(): TTaskId;

  get label(): string;

  get description(): string;

  get position(): ITaskPosition;

  // Internal
  get sourceTaskIds(): ReadonlySet<TTaskId>;
}

export type UTask = IEditedTask | IRunningTask;
