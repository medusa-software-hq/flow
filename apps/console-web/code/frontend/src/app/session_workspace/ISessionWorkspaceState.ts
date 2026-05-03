import { IEditingSessionWorkspaceState } from './editing/IEditingSessionWorkspaceState';
import { ISessionTrait } from './ISessionTrait';
import { IRunningSessionWorkspaceState } from './running/IRunningSessionWorkspaceState';
import { TSessionWorkspaceStateKind } from './SessionWorkspaceStateKinds';
import { ITaskGraph, UAnyTaskGraph } from './task_graph/ITaskGraph';

export interface ISessionWorkspaceState<$T extends ISessionTrait> extends Disposable {
  readonly kind: TSessionWorkspaceStateKind;

  get sessionTitle(): string;

  get exposedGenericTaskGraph(): ITaskGraph<$T>;

  get exposedAnyTaskGraph(): UAnyTaskGraph;
}

export type USessionWorkspaceState = IEditingSessionWorkspaceState | IRunningSessionWorkspaceState;
