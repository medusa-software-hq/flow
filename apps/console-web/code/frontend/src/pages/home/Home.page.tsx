import { AppShell } from '@mantine/core';
import { ReactFlowProvider } from '@xyflow/react';
import { useMemo, useState } from 'react';
import { proxy } from 'valtio';
import { FocusedTaskView } from '@/pages/home/components/FocusedTaskView/FocusedTaskView';
import { SessionCanvas } from '@/pages/home/components/SessionCanvas/SessionCanvas';
import { CSessionEditor } from '@/session_editor/CSessionEditor';
import { TTaskId } from '@/session_editor/CTask';
import classes from './Home.module.css';

export function HomePage() {
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
    <AppShell className={classes.shell} navbar={{ width: 320, breakpoint: 'sm' }}>
      <AppShell.Section className={classes.sidebar} p="md">
        <FocusedTaskView focusedTaskLive={focusedTaskLive} />
      </AppShell.Section>

      <AppShell.Section className={classes.main} p="md">
        <ReactFlowProvider>
          <SessionCanvas
            sessionEditorLive={sessionEditorLive}
            onTaskFocused={(taskId: TTaskId | null) => {
              console.log(`Setting focused task id to ${String(taskId)}`);

              setFocusedTaskId(taskId);
            }}
          />
        </ReactFlowProvider>
      </AppShell.Section>
    </AppShell>
  );
}
