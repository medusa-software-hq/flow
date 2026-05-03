import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';
import { ITask, ITaskPosition } from '../ITask';
import { TTaskId } from './CEditedTask';

export interface IEditedTask extends ITask {
  readonly kind: typeof SessionWorkspaceStateKinds.Editing;

  set label(value: string);

  set description(value: string);

  move(newPosition: ITaskPosition): void;

  // Internal
  addSourceTask(sourceTaskId: TTaskId): void;
}
