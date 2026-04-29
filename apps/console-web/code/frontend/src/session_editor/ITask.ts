export interface ITaskPosition {
  readonly x: number;
  readonly y: number;
}

export interface ITask {
  get label(): string;

  set label(value: string);

  get description(): string;

  set description(value: string);

  get position(): ITaskPosition;

  move(newPosition: ITaskPosition): void;
}
