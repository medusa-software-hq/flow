import { proxySet } from 'valtio/utils';
import type { ITask, ITaskPosition } from './ITask';

export type TTaskId = bigint;

export interface TaskProps {
  readonly initialSourceTaskId: TTaskId | null;
  readonly initialPosition: ITaskPosition;
}

const defaultTaskLabel = '';

export class CTask implements ITask {
  private _label = defaultTaskLabel;
  private _description = '';

  private _position: ITaskPosition;

  // Internal
  readonly _sourceTaskIds: Set<TTaskId>;

  constructor(props: TaskProps) {
    const { initialSourceTaskId } = props;

    const rawSourceTaskIds: Set<TTaskId> = (() => {
      if (initialSourceTaskId !== null) {
        return new Set([initialSourceTaskId]);
      }
      return new Set();
    })();

    this._sourceTaskIds = proxySet(rawSourceTaskIds);

    if (initialSourceTaskId !== null) {
      this._sourceTaskIds = new Set([initialSourceTaskId]);
    } else {
      this._sourceTaskIds = new Set();
    }

    this._position = props.initialPosition;
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
}
