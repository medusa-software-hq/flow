import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { IEditedTask, IRunningTask, ITaskPosition } from '@/app/session/ITask';
import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';

export interface CRunningTaskArgs {
  readonly id: TTaskId;
  readonly sourceTaskIds: ReadonlySet<TTaskId>;
  readonly position: ITaskPosition;
  readonly label: string;
  readonly description: string;
}

export class CRunningTask implements IRunningTask {
  static freeze(editedTask: IEditedTask): CRunningTask {
    return new CRunningTask(editedTask);
  }

  readonly kind = SessionWorkspaceStateKinds.Running;

  readonly id: TTaskId;
  readonly label: string;
  readonly description: string;
  readonly position: ITaskPosition;
  readonly sourceTaskIds: ReadonlySet<TTaskId>;

  private constructor(args: CRunningTaskArgs) {
    this.id = args.id;
    this.sourceTaskIds = args.sourceTaskIds;
    this.label = args.label;
    this.description = args.description;
    this.position = args.position;
  }

  getProgress(): number {
    return 0.5;
  }
}
