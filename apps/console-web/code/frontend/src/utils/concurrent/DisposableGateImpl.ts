import { DisposableGate } from './DisposableGate';
import { Gate } from './Gate';
import { GateImpl } from './GateImpl';

export class DisposableGateImpl implements DisposableGate {
  private _innerGate: Gate | null = new GateImpl();

  get isOpen(): boolean {
    return this._innerGate?.isOpen ?? false;
  }

  lift(): void {
    const innerGate = this._innerGate;

    if (innerGate === null) {
      throw new Error('Cannot lift a disposed gate');
    }

    innerGate.lift();
  }

  async enterThrough(): Promise<void> {
    const innerGate = this._innerGate;

    if (innerGate === null) {
      throw new Error('Cannot enter through a disposed gate');
    }

    await innerGate.enterThrough();
  }

  [Symbol.dispose](): void {
    this._innerGate = null;
  }
}
