import { proxySet } from 'valtio/utils';
import { ISessionEditNotifier } from '@/app/session_workspace/editing/ISessionEditNotifier';
import { PbTask } from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';
import type { ITaskPosition } from '../ITask';
import { IEditedTask } from './IEditedTask';

export type TTaskId = bigint;

const defaultTaskLabel = '';

export class CEditedTask implements IEditedTask {
  static restore(args: {
    sessionEditNotifier: ISessionEditNotifier;
    receivedTask: PbTask;
  }): CEditedTask {
    const { receivedTask } = args;

    const taskId = BigInt(receivedTask.id);

    return new CEditedTask({
      sessionEditNotifier: args.sessionEditNotifier,
      id: taskId,
      initialSourceTaskIds: new Set(
        receivedTask.sourceTaskIds.map((sourceTaskId) => BigInt(sourceTaskId))
      ),
      initialPosition: { x: receivedTask.x, y: receivedTask.y },
      initialLabel: receivedTask.label,
      initialDescription: receivedTask.description,
    });
  }

  static createNew(args: {
    sessionEditNotifier: ISessionEditNotifier;
    id: TTaskId;
    initialSourceTaskIds: Set<TTaskId>;
    initialPosition: ITaskPosition;
  }): CEditedTask {
    return new CEditedTask({
      sessionEditNotifier: args.sessionEditNotifier,
      id: args.id,
      initialSourceTaskIds: args.initialSourceTaskIds,
      initialPosition: args.initialPosition,
      initialLabel: '',
      initialDescription: '',
    });
  }

  readonly kind = SessionWorkspaceStateKinds.Editing;
  readonly id: TTaskId;

  private readonly _sessionEditNotifier: ISessionEditNotifier;
  private _label = defaultTaskLabel;
  private _description = '';
  private _position: ITaskPosition;

  private readonly _sourceTaskIds: Set<TTaskId>;

  private constructor(args: {
    sessionEditNotifier: ISessionEditNotifier;
    id: TTaskId;
    initialSourceTaskIds: Set<TTaskId>;
    initialPosition: ITaskPosition;
    initialLabel: string;
    initialDescription?: string;
  }) {
    this.id = args.id;

    this._sessionEditNotifier = args.sessionEditNotifier;
    this._label = args.initialLabel;
    this._description = args.initialDescription ?? '';
    this._sourceTaskIds = proxySet(args.initialSourceTaskIds);
    this._position = args.initialPosition;
  }

  get label(): string {
    return this._label;
  }

  set label(value: string) {
    this._label = value;
    this._finishEdit();
  }

  get description(): string {
    return this._description;
  }

  set description(value: string) {
    this._description = value;
    this._finishEdit();
  }

  get position(): ITaskPosition {
    return this._position;
  }

  move(newPosition: ITaskPosition) {
    this._position = newPosition;
    this._finishEdit();
  }

  // Internal
  get sourceTaskIds(): Set<bigint> {
    return this._sourceTaskIds;
  }

  // Internal
  addSourceTask(sourceTaskId: TTaskId): void {
    this._sourceTaskIds.add(sourceTaskId);
    this._finishEdit();
  }

  private _finishEdit() {
    this._sessionEditNotifier.notifyEdited();
  }
}
