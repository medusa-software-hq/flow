import { IEditedSessionTrait } from '@/app/session_workspace/ISessionTrait';
import { ISessionWorkspaceState } from '@/app/session_workspace/ISessionWorkspaceState';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import { IEditedTaskGraph } from '../task_graph/edited/IEditedTaskGraph';

export interface ISessionStartHandler {
  handleStartedSession(): void;
}

export interface IEditingSessionWorkspaceState extends ISessionWorkspaceState<IEditedSessionTrait> {
  readonly kind: typeof SessionWorkspaceStateKinds.Editing;

  readonly editedTaskGraph: IEditedTaskGraph;

  get sessionTitle(): string;

  set sessionTitle(value: string);

  check(): void;

  start(): void;
}
