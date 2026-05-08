import { proxySet } from 'valtio/utils';
import { ISessionEditNotifier } from '@/app/session_workspace/editing/ISessionEditNotifier';
import {
  IFeatureTaskDefinition,
  TaskDefinitionKinds,
  TaskDefinitionUtils,
  UTaskDefinition,
} from '@/app/session_workspace/task_graph/ITaskDefinition';
import { PbTask } from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { SessionWorkspaceStateKinds } from '../../SessionWorkspaceStateKinds';
import type { ITaskPosition } from '../ITask';
import { IEditedTask } from './IEditedTask';

export type TTaskId = bigint;

export class CEditedTask implements IEditedTask {
  static restore(args: {
    sessionEditNotifier: ISessionEditNotifier;
    receivedTask: PbTask;
  }): CEditedTask {
    const { receivedTask } = args;

    const taskId = BigInt(receivedTask.id);

    const receivedDefinition = TaskDefinitionUtils.load(receivedTask.definition);

    return new CEditedTask({
      sessionEditNotifier: args.sessionEditNotifier,
      id: taskId,
      initialSourceTaskIds: new Set(
        receivedTask.sourceTaskIds.map((sourceTaskId) => BigInt(sourceTaskId))
      ),
      initialPosition: { x: receivedTask.x, y: receivedTask.y },
      initialDefinition: receivedDefinition,
    });
  }

  static createNew(args: {
    sessionEditNotifier: ISessionEditNotifier;
    id: TTaskId;
    initialSourceTaskIds: Set<TTaskId>;
    initialPosition: ITaskPosition;
  }): CEditedTask {
    const initialDefinition: IFeatureTaskDefinition = {
      kind: TaskDefinitionKinds.Feature,
      label: '',
      description: '',
    };

    return new CEditedTask({
      sessionEditNotifier: args.sessionEditNotifier,
      id: args.id,
      initialSourceTaskIds: args.initialSourceTaskIds,
      initialPosition: args.initialPosition,
      initialDefinition: initialDefinition,
    });
  }

  readonly kind = SessionWorkspaceStateKinds.Editing;
  readonly id: TTaskId;

  private readonly _sessionEditNotifier: ISessionEditNotifier;

  private _definition: UTaskDefinition;
  private _position: ITaskPosition;
  private readonly _sourceTaskIds: Set<TTaskId>;

  private constructor(args: {
    sessionEditNotifier: ISessionEditNotifier;
    id: TTaskId;
    initialSourceTaskIds: Set<TTaskId>;
    initialPosition: ITaskPosition;
    initialDefinition: UTaskDefinition;
  }) {
    this.id = args.id;

    this._sessionEditNotifier = args.sessionEditNotifier;
    this._definition = args.initialDefinition;
    this._sourceTaskIds = proxySet(args.initialSourceTaskIds);
    this._position = args.initialPosition;
  }

  get definition(): UTaskDefinition {
    return this._definition;
  }

  set definition(value: UTaskDefinition) {
    this._definition = value;
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
