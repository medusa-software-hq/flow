import { ReactFlowProvider } from '@xyflow/react';
import { useSnapshot } from 'valtio';
import { ISessionWorkspace } from '@/app/ISessionWorkspace';
import { USessionWorkspaceState } from '@/app/ISessionWorkspaceState';
import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { SessionWorkspaceStateKinds } from '@/app/SessionWorkspaceStateKinds';
import { SessionCanvas } from '../SessionCanvas/SessionCanvas';

export interface SessionMainViewProps {
  readonly sessionWorkspaceLive: ISessionWorkspace;
  readonly onTaskFocused: (taskId: TTaskId | null) => void;
}

export function SessionMainView({ sessionWorkspaceLive, onTaskFocused }: SessionMainViewProps) {
  const sessionWorkspaceSnap = useSnapshot(sessionWorkspaceLive);

  void sessionWorkspaceSnap.currentState;

  const currentSessionWorkspaceStateLive: USessionWorkspaceState =
    sessionWorkspaceLive.currentState;

  const extractSessionLive = () => {
    switch (currentSessionWorkspaceStateLive.kind) {
      case SessionWorkspaceStateKinds.Editing: {
        const sessionEditorLive = currentSessionWorkspaceStateLive.sessionEditor;
        return sessionEditorLive.editedSession;
      }

      case SessionWorkspaceStateKinds.Running: {
        return currentSessionWorkspaceStateLive.runningSession;
      }
    }
  };

  const sessionLive = extractSessionLive();

  return (
    <ReactFlowProvider>
      <SessionCanvas
        sessionLive={sessionLive}
        sessionWorkspaceLive={sessionWorkspaceLive}
        onTaskFocused={onTaskFocused}
      />
    </ReactFlowProvider>
  );
}
