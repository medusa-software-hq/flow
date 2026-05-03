import { Disposable } from 'vitest/optional-runtime-types.js';
import { Gate } from './Gate';

export interface DisposableGate extends Gate, Disposable {}
