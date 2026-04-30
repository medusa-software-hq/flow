import { proxyMap } from 'valtio/utils';
import { AppStateKinds } from '@/app/AppStateKinds';
import { IRunningSessionTrait } from '@/app/ISessionTrait';
import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { IEditedTask } from '@/app/session/ITask';
import { CBaseSession } from '@/app/session/running_session/CBaseSession';
import { CRunningTask } from '@/app/session/running_session/CEditedTask';
import type { IEditedSession, IRunningSession } from '../ISession';

interface CRunningSessionArgs {
  readonly taskById: ReadonlyMap<TTaskId, IEditedTask>;
}

export class CRunningSession extends CBaseSession<IRunningSessionTrait> implements IRunningSession {
  static freeze(editedSession: IEditedSession): IRunningSession {
    return new CRunningSession({
      taskById: editedSession.taskById,
    });
  }

  readonly kind = AppStateKinds.Running;

  private constructor(args: CRunningSessionArgs) {
    super();

    this.taskById = proxyMap(
      mapValues(args.taskById, (editedTask) => CRunningTask.freeze(editedTask))
    );
  }

  readonly taskById: ReadonlyMap<TTaskId, CRunningTask>;

  get stamp(): unknown {
    return 0;
  }

  getSessionProgress(): number {
    return 0.2;
  }
}

function mapValues<K, V1, V2>(
  map: ReadonlyMap<K, V1>,
  valueMapper: (value: V1, key: K) => V2
): ReadonlyMap<K, V2> {
  return new Map(Array.from(map, ([key, value]) => [key, valueMapper(value, key)]));
}
