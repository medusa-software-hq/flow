import { IEditedSession } from '../session/ISession';

export interface ISessionEditor {
  readonly editedSession: IEditedSession;

  check(): void;

  start(): void;
}
