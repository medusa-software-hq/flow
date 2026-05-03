import { Lazy } from './Lazy';

export class Loop<T extends {}> implements Lazy<T> {
  private _loopedValue: T | null = null;

  get value(): T {
    const loopedValue = this._loopedValue;

    if (loopedValue === null) {
      throw new Error("Value wasn't looped yet");
    }

    return loopedValue;
  }

  close(value: T) {
    if (this._loopedValue !== null) {
      throw new Error('Value was already looped');
    }

    this._loopedValue = value;
  }
}
