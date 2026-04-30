import {
  SessionWorkspaceStateKinds,
  TSessionWorkspaceStateKind,
} from '../SessionWorkspaceStateKinds';
import { TTaskId } from './edited_session/CEditedTask';

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

export interface IEditedTask extends ITask {
  readonly kind: typeof SessionWorkspaceStateKinds.Editing;

  readonly editedTaskLabel: string;

  set label(value: string);

  set description(value: string);

  move(newPosition: ITaskPosition): void;

  // Internal
  addSourceTask(sourceTaskId: TTaskId): void;
}

export interface IRunningTask extends ITask {
  readonly kind: typeof SessionWorkspaceStateKinds.Running;

  getProgress(): number;
}

export type UTask = IEditedTask | IRunningTask;
