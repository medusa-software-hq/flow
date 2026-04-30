import { ReactFlowProvider } from '@xyflow/react';
import { useSnapshot } from 'valtio';
import { AppStateKinds } from '@/app/AppStateKinds';
import { IApp } from '@/app/IApp';
import { UAppState } from '@/app/IAppState';
import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { SessionCanvas } from '../SessionCanvas/SessionCanvas';

export interface SessionMainViewProps {
  readonly appLive: IApp;
  readonly onTaskFocused: (taskId: TTaskId | null) => void;
}

export function SessionMainView({ appLive, onTaskFocused }: SessionMainViewProps) {
  const appSnap = useSnapshot(appLive);

  void appSnap.currentState;

  const currentAppStateLive: UAppState = appLive.currentState;

  const extractSessionLive = () => {
    switch (currentAppStateLive.kind) {
      case AppStateKinds.Editing: {
        const sessionEditorLive = currentAppStateLive.sessionEditor;
        return sessionEditorLive.editedSession;
      }

      case AppStateKinds.Running: {
        return currentAppStateLive.runningSession;
      }
    }
  };

  const sessionLive = extractSessionLive();

  return (
    <ReactFlowProvider>
      <SessionCanvas sessionLive={sessionLive} onTaskFocused={onTaskFocused} />
    </ReactFlowProvider>
  );
}
