import { Observable, Subscription } from 'rxjs';
import { DisposableGate } from './DisposableGate';
import { Gate } from './Gate';

export class LiftedOnEmissionGate implements DisposableGate {
  private readonly _innerGate = Gate.newDisposable();

  private readonly _subscription: Subscription;

  constructor(liftingObservable: Observable<unknown>) {
    this._subscription = liftingObservable.subscribe({
      next: () => {
        this._innerGate.lift();
      },
    });
  }

  get isOpen(): boolean {
    return this._innerGate.isOpen;
  }

  lift(): void {
    this._innerGate.lift();
  }

  enterThrough(): Promise<void> {
    return this._innerGate.enterThrough();
  }

  [Symbol.dispose](): void {
    this._subscription.unsubscribe();
    this._innerGate[Symbol.dispose]();
  }
}
