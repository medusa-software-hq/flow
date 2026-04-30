import { Center, Image, Loader, Stack, Text } from '@mantine/core';
import { useSnapshot } from 'valtio';
import crashImageUrl from '@/../assets/crash.png';
import { ISessionWorkspace } from '@/app/ISessionWorkspace';
import { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { SessionWorkspaceStateKinds } from '@/app/SessionWorkspaceStateKinds';
import { EditedTaskView } from '../FocusedTaskView/EditedTaskView';

export interface AppSidebarContentProps {
  readonly sessionWorkspaceLive: ISessionWorkspace;
  readonly focusedTaskId: TTaskId | null;
}

export function SessionSidebarView({
  sessionWorkspaceLive,
  focusedTaskId,
}: AppSidebarContentProps) {
  const sessionWorkspaceSnap: ISessionWorkspace = useSnapshot(sessionWorkspaceLive);

  void sessionWorkspaceSnap.currentState;

  const currentSessionWorkspaceStateLive = sessionWorkspaceLive.currentState;

  switch (currentSessionWorkspaceStateLive.kind) {
    case SessionWorkspaceStateKinds.Editing: {
      if (focusedTaskId === null) {
        return <Text>(no task)</Text>;
      }

      const sessionEditorLive = currentSessionWorkspaceStateLive.sessionEditor;
      const editedSessionLive = sessionEditorLive.editedSession;
      const focusedEditedTaskLive = editedSessionLive.getTaskById(focusedTaskId);

      if (focusedEditedTaskLive === null) {
        console.warn(`Focused task with id ${focusedTaskId} not found in edited session`);

        return <CrashIcon />;
      } else {
        return <EditedTaskView editedTaskLive={focusedEditedTaskLive} />;
      }
    }
    case SessionWorkspaceStateKinds.Running: {
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
