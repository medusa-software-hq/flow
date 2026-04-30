import { proxySet } from 'valtio/utils';
import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';
import type { IEditedTask, ITaskPosition } from '../ITask';

export type TTaskId = bigint;

export interface TaskProps {
  readonly id: TTaskId;
  readonly initialSourceTaskIds?: ReadonlySet<TTaskId>;
  readonly initialSourceTaskId?: TTaskId | null;
  readonly initialPosition: ITaskPosition;
  readonly initialLabel?: string;
  readonly initialDescription?: string;
}

const defaultTaskLabel = '';

export class CEditedTask implements IEditedTask {
  readonly kind = SessionWorkspaceStateKinds.Editing;

  readonly id: TTaskId;

  private _label = defaultTaskLabel;
  private _description = '';

  private _position: ITaskPosition;

  private readonly _sourceTaskIds: Set<TTaskId>;

  constructor(props: TaskProps) {
    const { initialSourceTaskId = null, initialSourceTaskIds = null } = props;

    const rawSourceTaskIds: Set<TTaskId> =
      initialSourceTaskIds !== null
        ? new Set(initialSourceTaskIds)
        : initialSourceTaskId !== null
          ? new Set([initialSourceTaskId])
          : new Set();

    this.id = props.id;

    this._label = props.initialLabel ?? defaultTaskLabel;
    this._description = props.initialDescription ?? '';

    this._sourceTaskIds = proxySet(rawSourceTaskIds);

    this._position = props.initialPosition;
  }

  get editedTaskLabel(): string {
    return `Edited task!!!: ${this._label}`;
  }

  get label(): string {
    return this._label;
  }

  set label(value: string) {
    this._label = value;
  }

  get description(): string {
    return this._description;
  }

  set description(value: string) {
    this._description = value;
  }

  get position(): ITaskPosition {
    return this._position;
  }

  move(newPosition: ITaskPosition) {
    this._position = newPosition;
  }

  // Internal
  get sourceTaskIds(): Set<bigint> {
    return this._sourceTaskIds;
  }

  // Internal
  addSourceTask(sourceTaskId: TTaskId): void {
    this._sourceTaskIds.add(sourceTaskId);
  }
}
