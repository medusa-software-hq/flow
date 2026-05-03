import { Center, Image, Loader, Stack, Text } from '@mantine/core';
import { useSnapshot } from 'valtio';
import crashImageUrl from '@/../assets/crash.png';
import { ISessionWorkspace } from '@/app/session_workspace/ISessionWorkspace';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import { TTaskId } from '@/app/session_workspace/task_graph/edited/CEditedTask';
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
        return <EmptyTaskSelectionView />;
      }

      const editedSessionLive = currentSessionWorkspaceStateLive.editedTaskGraph;
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

function EmptyTaskSelectionView() {
  return (
    <Center h="100%">
      <Stack align="center" gap="xs">
        <Text size="lg">No task selected</Text>
        <Text size="sm" c="dimmed" ta="center">
          Pick a node on the canvas to inspect and edit its details.
        </Text>
      </Stack>
    </Center>
  );
}

function CrashIcon() {
  return (
    <Center h="100%">
      <Image src={crashImageUrl} alt="Missing focused task" maw={144} />
    </Center>
  );
}
