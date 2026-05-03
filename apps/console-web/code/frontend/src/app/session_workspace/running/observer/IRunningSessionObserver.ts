export interface ITaskProgressSnapshot {
  readonly progressByTaskId: ReadonlyMap<string, number>;
}

export interface IRunningSessionObserver {
  get lastReceivedSnapshot(): ITaskProgressSnapshot | null;
}
