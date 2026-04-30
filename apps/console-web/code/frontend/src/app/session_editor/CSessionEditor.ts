import { CEditedSession } from '@/app/session/edited_session/CEditedSession';
import { PbTaskGraph } from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { IEditedSession, IRunningSession } from '../session/ISession';
import { ISessionEditor } from './ISessionEditor';

export class CSessionEditor implements ISessionEditor {
  static createNew(onChanged?: () => void): ISessionEditor {
    return new CSessionEditor(CEditedSession.createNew(), onChanged);
  }

  static restore(taskGraph: PbTaskGraph | null, onChanged?: () => void): ISessionEditor {
    return new CSessionEditor(CEditedSession.restore(taskGraph), onChanged);
  }

  readonly editedSession: IEditedSession;
  readonly onChanged: (() => void) | null;

  private constructor(editedSession: IEditedSession, onChanged?: () => void) {
    this.editedSession = editedSession;
    this.onChanged = onChanged ?? null;
  }

  check(): void {}

  start(): void {}
}

export interface IRunningSessionObserver {
  readonly runningSession: IRunningSession;
}

export class CRunningSessionObserver implements ISessionEditor {
  static create(): ISessionEditor {
    return new CRunningSessionObserver(CEditedSession.createNew());
  }

  readonly editedSession: IEditedSession;
  readonly onChanged = null;

  private constructor(editedSession: IEditedSession) {
    this.editedSession = editedSession;
  }

  check(): void {}

  start(): void {}
}
