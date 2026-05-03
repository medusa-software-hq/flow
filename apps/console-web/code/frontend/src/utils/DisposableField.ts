import { Disposable } from 'vitest/optional-runtime-types.js';

export class DisposableField<T extends Disposable> implements Disposable {
  private _value: T;

  constructor(initialValue: T) {
    this._value = initialValue;
  }

  get(): T {
    return this._value;
  }

  set(value: T) {
    const oldValue = this._value;

    oldValue[Symbol.dispose]();

    this._value = value;
  }

  [Symbol.dispose](): void {
    const finalValue = this._value;

    finalValue[Symbol.dispose]();
  }
}
