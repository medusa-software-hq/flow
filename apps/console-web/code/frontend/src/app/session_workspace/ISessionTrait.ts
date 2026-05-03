import { IEditedTask } from './task_graph/edited/IEditedTask';
import { UTask } from './task_graph/ITask';
import { IRunningTask } from './task_graph/running/IRunningTask';

export interface ISessionTrait {
  taskT: UTask;
}

export interface IEditedSessionTrait extends ISessionTrait {
  taskT: IEditedTask;
}

export interface IRunningSessionTrait extends ISessionTrait {
  taskT: IRunningTask;
}
