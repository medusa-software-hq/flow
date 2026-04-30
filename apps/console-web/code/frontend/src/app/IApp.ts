import { UAppState } from './IAppState';

export interface IApp {
  get currentState(): UAppState;

  freeze(): void;
}
