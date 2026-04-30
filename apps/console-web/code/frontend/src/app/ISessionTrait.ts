import { IEditedTask, IRunningTask, UTask } from './session/ITask';

export interface ISessionTrait {
  taskT: UTask;
}

export interface IEditedSessionTrait extends ISessionTrait {
  taskT: IEditedTask;
}

export interface IRunningSessionTrait extends ISessionTrait {
  taskT: IRunningTask;
}
