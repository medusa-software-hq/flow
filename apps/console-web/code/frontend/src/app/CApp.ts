import { create } from '@bufbuild/protobuf';
import { proxyMap } from 'valtio/utils';
import {
  ListSessionsRequestSchema,
  SessionSummary,
  TaskGraphSchema,
} from '@/gen/medusa/flow/core_service/v1/core_service_pb';
import { CSessionWorkspaceTrampoline } from '@/session_workspace_trampoline/CSessionWorkspaceTrampoline';
import { ISessionWorkspaceTrampoline } from '@/session_workspace_trampoline/ISessionWorkspaceTrampoline';
import { IApp, IAppLoadArgs, IAppSessionSummary, TSessionWorkspaceId } from './IApp';

const fakeSessionVisuals = [
  { label: 'i', tone: 'blue' },
  { label: 'v', tone: 'lime' },
  { label: '■', tone: 'ink' },
  { label: 'z', tone: 'violet' },
  { label: 'g', tone: 'pink' },
  { label: 'z', tone: 'violet' },
] as const;

export class CApp implements IApp {
  static async load({ coreServiceClient }: IAppLoadArgs): Promise<IApp> {
    const listSessionsResponse = await coreServiceClient.listSessions(
      create(ListSessionsRequestSchema)
    );

    return new CApp({
      coreServiceClient,
      existingSessions: listSessionsResponse.sessions,
    });
  }

  private _nextSessionWorkspaceNumber = 1;
  private _selectedSessionWorkspaceId: TSessionWorkspaceId | null = null;
  private readonly _coreServiceClient;

  private _sessionWorkspaceTrampolineById: Map<TSessionWorkspaceId, ISessionWorkspaceTrampoline> =
    proxyMap();

  private constructor(args: {
    coreServiceClient: IAppLoadArgs['coreServiceClient'];
    existingSessions: readonly SessionSummary[];
  }) {
    this._coreServiceClient = args.coreServiceClient;
    const firstExistingSessionId = args.existingSessions[0]?.sessionId ?? null;

    for (const sessionSummary of args.existingSessions) {
      const sessionWorkspaceId = sessionSummary.sessionId;

      this._sessionWorkspaceTrampolineById.set(
        sessionWorkspaceId,
        CSessionWorkspaceTrampoline.createProxied({
          coreServiceClient: this._coreServiceClient,
          restoredSessionSummary: sessionSummary,
        })
      );
    }

    this._nextSessionWorkspaceNumber = args.existingSessions.length + 1;
    this._selectedSessionWorkspaceId = firstExistingSessionId;
  }

  get selectedSessionWorkspaceId(): TSessionWorkspaceId | null {
    return this._selectedSessionWorkspaceId;
  }

  get selectedSessionWorkspaceTrampoline(): ISessionWorkspaceTrampoline | null {
    const selectedSessionWorkspaceId = this.selectedSessionWorkspaceId;

    if (selectedSessionWorkspaceId === null) {
      return null;
    }

    return this._sessionWorkspaceTrampolineById.get(selectedSessionWorkspaceId) ?? null;
  }

  get sessionWorkspaceTrampolineById(): ReadonlyMap<
    TSessionWorkspaceId,
    ISessionWorkspaceTrampoline
  > {
    return this._sessionWorkspaceTrampolineById;
  }

  get sessions(): readonly IAppSessionSummary[] {
    return Array.from(this._sessionWorkspaceTrampolineById.entries(), ([id, trampoline], index) => {
      const fakeSessionVisual = fakeSessionVisuals[index % fakeSessionVisuals.length];

      return {
        id,
        label: fakeSessionVisual.label,
        tone: fakeSessionVisual.tone,
        isSelected: id === this._selectedSessionWorkspaceId,
        stateKind: trampoline.currentState.kind,
        onSelected: () => this.selectSessionWorkspace(id),
      };
    });
  }

  createSessionWorkspace(): TSessionWorkspaceId {
    const newSessionWorkspaceId = `session-workspace-${this._nextSessionWorkspaceNumber++}`;

    const newSessionWorkspaceTrampoline = CSessionWorkspaceTrampoline.createProxied({
      coreServiceClient: this._coreServiceClient,
      initialTaskGraph: create(TaskGraphSchema),
    });

    this._sessionWorkspaceTrampolineById.set(newSessionWorkspaceId, newSessionWorkspaceTrampoline);
    this._selectedSessionWorkspaceId = newSessionWorkspaceId;

    return newSessionWorkspaceId;
  }

  selectSessionWorkspace(sessionWorkspaceId: TSessionWorkspaceId): void {
    if (!this._sessionWorkspaceTrampolineById.has(sessionWorkspaceId)) {
      throw new Error(`Session workspace with ID ${sessionWorkspaceId} not found`);
    }

    this._selectedSessionWorkspaceId = sessionWorkspaceId;
  }
}
