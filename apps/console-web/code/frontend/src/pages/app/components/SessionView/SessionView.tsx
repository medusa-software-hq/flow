import { AppShell } from '@mantine/core';
import { useState } from 'react';
import { ISessionWorkspace } from '@/app/ISessionWorkspace';
import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { SessionMainView } from '../AppMainView/SessionMainView';
import { SessionSidebarView } from '../AppSidebarContent/SessionSidebarView';
import classes from '../../AppPage.module.css';

export interface SessionViewProps {
  readonly sessionWorkspaceLive: ISessionWorkspace;
}

export function SessionView({ sessionWorkspaceLive }: SessionViewProps) {
  const [focusedTaskId, setFocusedTaskId] = useState<TTaskId | null>(null);

  return (
    <AppShell className={classes.shell} navbar={{ width: 320, breakpoint: 'sm' }}>
      <AppShell.Section className={classes.sidebar} p="md">
        <SessionSidebarView
          sessionWorkspaceLive={sessionWorkspaceLive}
          focusedTaskId={focusedTaskId}
        />
      </AppShell.Section>

      <SessionMainView
        sessionWorkspaceLive={sessionWorkspaceLive}
        onTaskFocused={setFocusedTaskId}
      />
    </AppShell>
  );
}
