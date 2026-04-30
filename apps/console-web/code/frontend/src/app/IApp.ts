import { UAppState } from './IAppState';

export type TAppSessionTone = 'blue' | 'lime' | 'ink' | 'violet' | 'pink';

export interface IAppSessionSummary {
  readonly id: string;
  readonly label: string;
  readonly tone: TAppSessionTone;
  readonly isSelected: boolean;
}

export interface IApp {
  get currentState(): UAppState;

  get sessions(): readonly IAppSessionSummary[];

  freeze(): void;
}
