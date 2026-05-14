import { UTaskDefinition } from '@/app/session_workspace/task_graph/ITaskDefinition';
import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';
import { ITask, ITaskPosition } from '../ITask';
import { TTaskId } from './CEditedTask';

export interface IEditedTask extends ITask {
  readonly kind: typeof SessionWorkspaceStateKinds.Editing;

  set definition(value: UTaskDefinition);

  move(newPosition: ITaskPosition): void;

  // Internal
  addSourceTask(sourceTaskId: TTaskId): void;
}
