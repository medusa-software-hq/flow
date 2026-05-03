import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';
import { ITask } from '../ITask';

export interface IRunningTask extends ITask {
  readonly kind: typeof SessionWorkspaceStateKinds.Running;

  getProgress(): number;
}
