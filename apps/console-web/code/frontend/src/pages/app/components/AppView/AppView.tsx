import { AppShell, Button, Group } from '@mantine/core';
import { useState } from 'react';
import { AppStateKinds } from '@/app/AppStateKinds';
import { IApp } from '@/app/IApp';
import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { SessionMainView } from '../AppMainView/SessionMainView';
import { SessionSidebarView } from '../AppSidebarContent/SessionSidebarView';
import classes from '../../AppPage.module.css';

export interface AppViewProps {
  readonly appLive: IApp;
}

export function AppView({ appLive }: AppViewProps) {
  const [focusedTaskId, setFocusedTaskId] = useState<TTaskId | null>(null);
  const isEditing = appLive.currentState.kind === AppStateKinds.Editing;

  return (
    <div className={classes.page}>
      <Group className={classes.toolbar} justify="flex-end" p="md">
        <Button onClick={() => appLive.freeze()} disabled={!isEditing}>
          Freeze For Testing
        </Button>
      </Group>

      <AppShell className={classes.shell} navbar={{ width: 320, breakpoint: 'sm' }}>
        <AppShell.Section className={classes.sidebar} p="md">
          <SessionSidebarView appLive={appLive} focusedTaskId={focusedTaskId} />
        </AppShell.Section>

        <SessionMainView appLive={appLive} onTaskFocused={setFocusedTaskId} />
      </AppShell>
    </div>
  );
}
