import { AppShell } from '@mantine/core';
import { useState } from 'react';
import { IApp } from '@/app/IApp';
import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { SessionMainView } from '../AppMainView/SessionMainView';
import { SessionSidebarView } from '../AppSidebarContent/SessionSidebarView';
import classes from '../../AppPage.module.css';

export interface SessionViewProps {
  readonly appLive: IApp;
}

export function SessionView({ appLive }: SessionViewProps) {
  const [focusedTaskId, setFocusedTaskId] = useState<TTaskId | null>(null);

  return (
    <AppShell className={classes.shell} navbar={{ width: 320, breakpoint: 'sm' }}>
      <AppShell.Section className={classes.sidebar} p="md">
        <SessionSidebarView appLive={appLive} focusedTaskId={focusedTaskId} />
      </AppShell.Section>

      <SessionMainView appLive={appLive} onTaskFocused={setFocusedTaskId} />
    </AppShell>
  );
}
