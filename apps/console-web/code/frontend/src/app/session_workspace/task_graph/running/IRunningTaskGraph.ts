import { IRunningSessionTrait } from '../../ISessionTrait';
import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';
import { ITaskGraph } from '../ITaskGraph';

export interface IRunningTaskGraph extends ITaskGraph<IRunningSessionTrait> {
  readonly kind: typeof SessionWorkspaceStateKinds.Running;

  getSessionProgress(): number;
}
