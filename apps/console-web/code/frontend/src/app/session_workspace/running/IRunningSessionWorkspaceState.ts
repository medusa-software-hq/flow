import { IRunningSessionTrait } from '@/app/session_workspace/ISessionTrait';
import { ISessionWorkspaceState } from '@/app/session_workspace/ISessionWorkspaceState';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import { IRunningTaskGraph } from '../task_graph/running/IRunningTaskGraph';

export interface IRunningSessionWorkspaceState extends ISessionWorkspaceState<IRunningSessionTrait> {
  readonly kind: typeof SessionWorkspaceStateKinds.Running;

  readonly runningTaskGraph: IRunningTaskGraph;
}
