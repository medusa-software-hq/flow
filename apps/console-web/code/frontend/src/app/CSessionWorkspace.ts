import { create } from '@bufbuild/protobuf';
import { IEditingState, IRunningState, USessionWorkspaceState } from '@/app/ISessionWorkspaceState';
import { IEditedSession } from '@/app/session/ISession';
import { CRunningSession } from '@/app/session/running_session/CRunningSession';
import { CSessionEditor } from '@/app/session_editor/CSessionEditor';
import {
  SessionSummary,
  TaskGraphSchema,
  TaskSchema,
  UpdateSessionRequestSchema,
} from '@/gen/medusa/flow/core_service/v1/core_service_pb';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { ISessionWorkspace } from './ISessionWorkspace';
import { SessionWorkspaceStateKinds } from './SessionWorkspaceStateKinds';

export class CSessionWorkspace implements ISessionWorkspace {
  static async createNew(
    coreServiceClient: CoreServiceClient,
    sessionId: string
  ): Promise<ISessionWorkspace> {
    const self = new CSessionWorkspace(coreServiceClient, sessionId);
    const initialSessionEditor = CSessionEditor.createNew(() => {
      void self.upload();
    });

    const initialState: IEditingState = {
      kind: SessionWorkspaceStateKinds.Editing,
      sessionEditor: initialSessionEditor,
    };

    self._currentState = initialState;

    return self;
  }

  static restore(
    coreServiceClient: CoreServiceClient,
    sessionSummary: SessionSummary
  ): ISessionWorkspace {
    const self = new CSessionWorkspace(coreServiceClient, sessionSummary.sessionId);
    const restoredSessionEditor = CSessionEditor.restore(sessionSummary.taskGraph ?? null, () => {
      void self.upload();
    });

    const initialState: IEditingState = {
      kind: SessionWorkspaceStateKinds.Editing,
      sessionEditor: restoredSessionEditor,
    };

    self._currentState = initialState;

    return self;
  }

  _currentState: USessionWorkspaceState;
  private readonly _coreServiceClient: CoreServiceClient;
  private readonly _sessionId: string;

  private constructor(coreServiceClient: CoreServiceClient, sessionId: string) {
    this._coreServiceClient = coreServiceClient;
    this._sessionId = sessionId;
    this._currentState = {
      kind: SessionWorkspaceStateKinds.Editing,
      sessionEditor: CSessionEditor.createNew(),
    };
  }

  get currentState(): USessionWorkspaceState {
    return this._currentState;
  }

  async upload(): Promise<void> {
    const currentState = this.currentState;

    if (currentState.kind !== SessionWorkspaceStateKinds.Editing) {
      return;
    }

    await this._coreServiceClient.updateSession(
      create(UpdateSessionRequestSchema, {
        sessionId: this._sessionId,
        taskGraph: serializeTaskGraph(currentState.sessionEditor.editedSession),
      })
    );
  }

  freeze(): void {
    const currentState = this.currentState;

    if (currentState.kind !== SessionWorkspaceStateKinds.Editing) {
      throw new Error('Cannot freeze app state: current state is not editing');
    }

    const sessionEditor = currentState.sessionEditor;

    const frozenRunningSession = CRunningSession.freeze(sessionEditor.editedSession);

    const frozenState: IRunningState = {
      kind: SessionWorkspaceStateKinds.Running,
      runningSession: frozenRunningSession,
    };

    this._currentState = frozenState;
  }
}

function serializeTaskGraph(editedSession: IEditedSession) {
  return create(TaskGraphSchema, {
    tasks: Array.from(editedSession.taskById.values(), (task) =>
      create(TaskSchema, {
        id: String(task.id),
        label: task.label,
        description: task.description,
        sourceTaskIds: Array.from(task.sourceTaskIds, String),
        x: task.position.x,
        y: task.position.y,
      })
    ),
  });
}
