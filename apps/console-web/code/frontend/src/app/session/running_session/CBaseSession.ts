import { TAppStateKind } from '@/app/AppStateKinds';
import { ISessionTrait } from '@/app/ISessionTrait';
import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import type { ISession } from '../ISession';

export abstract class CBaseSession<$T extends ISessionTrait> implements ISession<$T> {
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

  abstract readonly kind: TAppStateKind;

  abstract get stamp(): unknown;

  abstract get taskById(): ReadonlyMap<TTaskId, $T['taskT']>;
}
