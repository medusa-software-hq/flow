import { TSessionWorkspaceStateKind } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import { ISessionTrait } from '../../ISessionTrait';
import { TTaskId } from '../edited/CEditedTask';
import type { ITaskGraph } from '../ITaskGraph';

export abstract class CBaseTaskGraph<$T extends ISessionTrait> implements ITaskGraph<$T> {
  getTaskById(taskId: TTaskId): $T['taskT'] | null {
    return this.taskById.get(taskId) ?? null;
  }

  getSourceTasks(targetTaskId: TTaskId): ReadonlySet<$T['taskT']> {
    const targetTask = this.getTaskById(targetTaskId);

    if (targetTask === null) {
      throw new Error(`Source task with ID ${String(targetTaskId)} not found`);
    }

    return new Set(
      [...targetTask.sourceTaskIds].map((sourceTaskId) => {
        const sourceTask = this.getTaskById(sourceTaskId);

        if (sourceTask === null) {
          throw new Error(`Source task with ID ${String(sourceTaskId)} not found`);
        }

        return sourceTask;
      })
    );
  }

  getTargetTasks(sourceTaskId: TTaskId): ReadonlySet<$T['taskT']> {
    const sourceTask = this.getTaskById(sourceTaskId);

    if (sourceTask === null) {
      throw new Error(`Source task with ID ${String(sourceTaskId)} not found`);
    }

    return new Set(
      [...this.taskById.values()].filter((task) => task.sourceTaskIds.has(sourceTaskId))
    );
  }

  abstract readonly kind: TSessionWorkspaceStateKind;

  abstract get stamp(): unknown;

  abstract get taskById(): ReadonlyMap<TTaskId, $T['taskT']>;
}
