import { IEditedSession } from '../session/ISession';

export interface ISessionEditor {
  readonly editedSession: IEditedSession;
  readonly onChanged: (() => void) | null;

  check(): void;

  start(): void;
}
