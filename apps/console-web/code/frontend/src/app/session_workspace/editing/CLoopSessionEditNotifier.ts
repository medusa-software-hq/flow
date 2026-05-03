import { Subject } from 'rxjs';
import { ISessionEditNotifier } from './ISessionEditNotifier';

export class CLoopSessionEditNotifier implements ISessionEditNotifier {
  private _onSessionEdited: Subject<void> | null = null;

  notifyEdited(): void {
    const onEdited = this._onSessionEdited;

    if (onEdited === null) {
      throw new Error('Session edit notifier is looped yet');
    }

    onEdited.next();
  }

  close(onSessionEdited: Subject<void>) {
    if (this._onSessionEdited !== null) {
      throw new Error('Session edit notifier is already looped');
    }

    this._onSessionEdited = onSessionEdited;
  }
}
