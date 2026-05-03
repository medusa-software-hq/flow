import { TSessionWorkspaceStateKind } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import { ISessionTrait } from '../ISessionTrait';
import { type TTaskId } from './edited/CEditedTask';
import { IEditedTaskGraph } from './edited/IEditedTaskGraph';
import { IRunningTaskGraph } from './running/IRunningTaskGraph';

export type TAnyTaskGraph = ITaskGraph<ISessionTrait>;

export interface ITaskGraph<$T extends ISessionTrait> {
  readonly kind: TSessionWorkspaceStateKind;

  get stamp(): unknown;

  get taskById(): ReadonlyMap<TTaskId, $T['taskT']>;

  getTaskById(taskId: TTaskId): $T['taskT'] | null;

  getSourceTasks(sourceTaskId: TTaskId): ReadonlySet<$T['taskT']>;

  getTargetTasks(sourceTaskId: TTaskId): ReadonlySet<$T['taskT']>;
}

export type UAnyTaskGraph = IEditedTaskGraph | IRunningTaskGraph;
