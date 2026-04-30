import { Center, Image, Loader, Stack, Text } from '@mantine/core';
import { useSnapshot } from 'valtio';
import crashImageUrl from '@/../assets/crash.png';
import { IApp } from '@/app/IApp';
import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { AppStateKinds } from '../../../../app/AppStateKinds';
import { EditedTaskView } from '../FocusedTaskView/EditedTaskView';

export interface AppSidebarContentProps {
  readonly appLive: IApp;
  readonly focusedTaskId: TTaskId | null;
}

export function AppSidebarContent({ appLive, focusedTaskId }: AppSidebarContentProps) {
  const appSnap: IApp = useSnapshot(appLive);

  void appSnap.currentState;

  const currentAppStateLive = appLive.currentState;

  switch (currentAppStateLive.kind) {
    case AppStateKinds.Editing: {
      if (focusedTaskId === null) {
        return <Text>(no task)</Text>;
      }

      const sessionEditorLive = currentAppStateLive.sessionEditor;
      const editedSessionLive = sessionEditorLive.editedSession;
      const focusedEditedTaskLive = editedSessionLive.getTaskById(focusedTaskId);

      if (focusedEditedTaskLive === null) {
        console.warn(`Focused task with id ${focusedTaskId} not found in edited session`);

        return <CrashIcon />;
      } else {
        return <EditedTaskView editedTaskLive={focusedEditedTaskLive} />;
      }
    }
    case AppStateKinds.Running: {
      return (
        <Center h="100%">
          <Stack align="center" gap="xs">
            <Loader />
            <Text size="sm" c="dimmed">
              Running...
            </Text>
          </Stack>
        </Center>
      );
    }
  }
}

function CrashIcon() {
  return (
    <Center h="100%">
      <Image src={crashImageUrl} alt="Missing focused task" maw={144} />
    </Center>
  );
}
