type ResolveFn = () => void;

/**
 * A helper synchronization primitive that allows threads to wait until it's their turn to proceed.
 */
export class WaitingQueue {
  private readonly _resolveFns: ResolveFn[] = [];

  /**
   * @return true if there are threads waiting in the queue, false otherwise
   */
  get isBlocked(): boolean {
    return this._resolveFns.length > 0;
  }

  /**
   * Releases the next waiting thread, if any.
   *
   * @return true if a waiting thread was released, false if there were no waiting threads
   */
  releaseSingle(): boolean {
    // Let's pop the next waiting thread, if any
    const resolveFn = this._resolveFns.shift();

    if (resolveFn === undefined) {
      // Nobody is waiting, unlock the mutex
      return false;
    } else {
      // Release the next waiting thread
      resolveFn();

      return true;
    }
  }

  /**
   * Waits until it's this thread's turn to proceed. This method never returns immediately.
   */
  async wait(): Promise<void> {
    const { promise, resolve } = Promise.withResolvers<void>();

    // Enqueue the resolve function
    this._resolveFns.push(resolve);

    // Wait for our turn
    await promise;
  }
}
