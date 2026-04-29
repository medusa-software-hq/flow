import '@xyflow/react/dist/style.css';
import './App.css';

import type { CoreServiceClient } from '../rpc/myGrpcTypes.ts';
import { SessionCanvas } from './session_canvas/SessionCanvas.tsx';
import { FocusedTaskView } from './sidebar/FocusedTaskView.tsx';
import { ReactFlowProvider } from '@xyflow/react';
import { CSessionEditor } from '../session_editor/CSessionEditor.ts';
import { proxy } from 'valtio';
import { useMemo, useState } from 'react';
import type { TTaskId } from '../session_editor/CTask.ts';

interface AppProps {
  readonly coreServiceClient: CoreServiceClient;
}

function App(props: AppProps) {
  const { coreServiceClient } = props;

  void coreServiceClient; // TODO: Use the service

  const sessionEditorLive = useMemo(() => proxy(new CSessionEditor()), []);

  const [focusedTaskId, setFocusedTaskId] = useState<TTaskId | null>(null);

  const getFocusedTaskLive = () => {
    switch (focusedTaskId) {
      case null:
        return null;
      default:
        return sessionEditorLive.getTaskById(focusedTaskId);
    }
  };

  const focusedTaskLive = getFocusedTaskLive();

  return (
    <main className="app-shell">
      <section className="info-panel">
        <FocusedTaskView focusedTaskLive={focusedTaskLive} />
      </section>

      <section className="flow-shell" aria-label="React Flow demo canvas">
        <ReactFlowProvider>
          <SessionCanvas
            sessionEditorLive={sessionEditorLive}
            onTaskFocused={(taskId: TTaskId | null) => {
              console.log(`Setting focused task id to ${String(taskId)}`);

              setFocusedTaskId(taskId);
            }}
          />
        </ReactFlowProvider>
      </section>
    </main>
  );
}

export default App;
