import { Observable } from 'rxjs';
import { DisposableGate } from '@/utils/concurrent/DisposableGate';
import { DisposableGateImpl } from '@/utils/concurrent/DisposableGateImpl';
import { GateImpl } from '@/utils/concurrent/GateImpl';
import { LiftedOnEmissionGate } from './LiftedOnEmissionGate';

/**
 * A gate is a synchronization primitive that allows threads to wait until it's the right time to proceed. Unlike the
 * lock, the gate does not protect any resource, but can be used to control the rate at which threads are allowed to
 * proceed.
 *
 * In a typical scenario, the thread that lifts the gate is a different one from the threads that enter through it.
 */
export interface Gate {
  /**
   * @return true if the gate is open, false otherwise.
   */
  get isOpen(): boolean;

  /**
   * Lift the gate. If there are any threads waiting to enter through, one of them will be released and the gate will
   * end up closed. If there are no threads waiting, the gate will remain open.
   */
  lift(): void;

  /**
   * Enter through the gate. If the gate is open, it will be closed and the method will return immediately. If the gate
   * is closed, the method will block until the gate is lifted.
   */
  enterThrough(): Promise<void>;
}

export const Gate = {
  new(): Gate {
    return new GateImpl();
  },

  newDisposable(): DisposableGate {
    return new DisposableGateImpl();
  },

  liftedOnEmission(liftingObservable: Observable<unknown>): DisposableGate {
    return new LiftedOnEmissionGate(liftingObservable);
  },
};
