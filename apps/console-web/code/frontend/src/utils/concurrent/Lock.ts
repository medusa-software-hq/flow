import { WaitingQueue } from '@/utils/concurrent/WaitingQueue';

type ReleaseFn = () => void;

/**
 * A lock (also called a _mutex_, short for "mutual exclusion") is a synchronization primitive that allows only one
 * thread to access a resource at a time.
 *
 * In a typical scenario, the thread that acquires the lock is the one that releases it.
 */
export class Lock {
  static async process<T>(mutex: Lock, block: () => Promise<T>): Promise<T> {
    const unlock = await mutex.acquire();

    try {
      return await block();
    } finally {
      unlock();
    }
  }

  private _isLocked: boolean = false;
  private _waitingQueue = new WaitingQueue();

  /**
   * @return true if the lock is currently locked, false otherwise.
   */
  isLocked(): boolean {
    return this._isLocked;
  }

  /**
   * Acquires the lock. If the lock is already locked, the method will block until the lock is released by another
   * thread. Otherwise, the method will return immediately. Once this method returns, the caller has ownership over the
   * resource protected by the lock.
   *
   * @return a function that releases the lock when called. The caller is responsible for calling this function exactly
   * once to release the lock when it's done with the resource.
   */
  async acquire(): Promise<ReleaseFn> {
    // A helper flag to detect double-release errors
    let wasUnlocked = false;

    const release: ReleaseFn = () => {
      if (wasUnlocked) {
        // Double-release error
        throw new Error('Unlock function called multiple times');
      }

      // Hand over the ownership over the resource to the next waiting thread (if any)
      const wasReleased = this._waitingQueue.releaseSingle();

      if (!wasReleased) {
        // Nobody was waiting, unlock the lock
        this._isLocked = false;
      }

      wasUnlocked = true;
    };

    if (this._isLocked) {
      // Lock is locked, let's wait for our turn
      await this._waitingQueue.wait();

      // Now we have ownership over the resource
      return release;
    } else {
      // Lock is unlocked (should imply that nobody is waiting)

      // Let's just lock it
      this._isLocked = true;

      // The thread is immediately given ownership over the resource
      return release;
    }
  }
}
