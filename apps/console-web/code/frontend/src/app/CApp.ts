import { proxyMap } from 'valtio/utils';
import { CSessionWorkspaceTrampoline } from '@/session_workspace_trampoline/CSessionWorkspaceTrampoline';
import { ISessionWorkspaceTrampoline } from '@/session_workspace_trampoline/ISessionWorkspaceTrampoline';
import { sleep } from '@/utils/promiseUtils';
import { IApp, IAppSessionSummary, TSessionWorkspaceId } from './IApp';

const fakeSessionVisuals = [
  { label: 'i', tone: 'blue' },
  { label: 'v', tone: 'lime' },
  { label: '■', tone: 'ink' },
  { label: 'z', tone: 'violet' },
  { label: 'g', tone: 'pink' },
  { label: 'z', tone: 'violet' },
] as const;

export class CApp implements IApp {
  static async load(): Promise<IApp> {
    await sleep(2000);

    if (Math.random() < 0.1) {
      throw new Error('Random app error!!!1');
    }

    return new CApp();
  }

  private _nextSessionWorkspaceNumber = 1;
  private _selectedSessionWorkspaceId: TSessionWorkspaceId | null = null;

  private _sessionWorkspaceTrampolineById: Map<TSessionWorkspaceId, ISessionWorkspaceTrampoline> =
    proxyMap();

  private constructor() {}

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

    const newSessionWorkspaceTrampoline = CSessionWorkspaceTrampoline.createProxied();

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
