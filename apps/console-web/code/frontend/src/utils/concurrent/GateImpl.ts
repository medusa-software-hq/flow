import { Gate } from '@/utils/concurrent/Gate';
import { WaitingQueue } from '@/utils/concurrent/WaitingQueue';

export class GateImpl implements Gate {
  private _isOpen: boolean = false;
  private _waitingQueue = new WaitingQueue();

  get isOpen(): boolean {
    return this._isOpen;
  }

  lift(): void {
    // Release the next waiting thread (if any)
    const wasReleased = this._waitingQueue.releaseSingle();

    // If any thread was waiting, the gate ends up closed. Otherwise, it remains open.
    this._isOpen = !wasReleased;
  }

  async enterThrough(): Promise<void> {
    if (this._isOpen) {
      this._isOpen = false;
    } else {
      await this._waitingQueue.wait();
    }
  }
}
