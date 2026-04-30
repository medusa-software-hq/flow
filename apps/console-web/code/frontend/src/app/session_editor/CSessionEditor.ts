import { CEditedSession } from '@/app/session/edited_session/CEditedSession';
import { IEditedSession, IRunningSession } from '../session/ISession';
import { ISessionEditor } from './ISessionEditor';

export class CSessionEditor implements ISessionEditor {
  static create(): ISessionEditor {
    return new CSessionEditor(CEditedSession.create());
  }

  readonly editedSession: IEditedSession;

  private constructor(editedSession: IEditedSession) {
    this.editedSession = editedSession;
  }

  check(): void {}

  start(): void {}
}

export interface IRunningSessionObserver {
  readonly runningSession: IRunningSession;
}

export class CRunningSessionObserver implements ISessionEditor {
  static create(): ISessionEditor {
    return new CRunningSessionObserver(CEditedSession.create());
  }

  readonly editedSession: IEditedSession;

  private constructor(editedSession: IEditedSession) {
    this.editedSession = editedSession;
  }

  check(): void {}

  start(): void {}
}
