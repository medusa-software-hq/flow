import { Center, Stack, Text } from '@mantine/core';
import { useSnapshot } from 'valtio';
import { ISessionWorkspace } from '@/app/session_workspace/ISessionWorkspace';
import { TTaskId } from '@/app/session_workspace/task_graph/edited/CEditedTask';
import { UTask } from '@/app/session_workspace/task_graph/ITask';
import { UAnyTaskGraph } from '@/app/session_workspace/task_graph/ITaskGraph';
import { FocusedTaskView } from '@/pages/app/components/FocusedTaskView/FocusedTaskView';
import { CrashIcon } from './CrashIcon';

export function SessionSidebarView(props: {
  readonly sessionWorkspaceLive: ISessionWorkspace;
  readonly focusedTaskId: TTaskId | null;
}) {
  const { sessionWorkspaceLive, focusedTaskId } = props;

  if (focusedTaskId !== null) {
    return (
      <SessionSidebarView$1
        sessionWorkspaceLive={sessionWorkspaceLive}
        focusedTaskId={focusedTaskId}
      />
    );
  } else {
    return <SessionSidebarView$Empty />;
  }
}

function SessionSidebarView$Empty() {
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

export function SessionSidebarView$1(props: {
  readonly sessionWorkspaceLive: ISessionWorkspace;
  readonly focusedTaskId: TTaskId;
}) {
  const { sessionWorkspaceLive, focusedTaskId } = props;

  const sessionWorkspaceSnap: ISessionWorkspace = useSnapshot(sessionWorkspaceLive);

  void sessionWorkspaceSnap.currentState;
  const currentSessionWorkspaceStateLive = sessionWorkspaceLive.currentState;

  const currentSessionWorkspaceStateSnap = useSnapshot(currentSessionWorkspaceStateLive);

  void currentSessionWorkspaceStateSnap.exposedAnyTaskGraph;
  const exposedTaskGraphLive: UAnyTaskGraph = currentSessionWorkspaceStateLive.exposedAnyTaskGraph;

  const focusedTaskLive: UTask | null = exposedTaskGraphLive.getTaskById(focusedTaskId);

  if (focusedTaskLive === null) {
    console.warn(`Focused task with id ${focusedTaskId} not found in current session workspace`);

    return <CrashIcon />;
  }

  return <FocusedTaskView focusedTaskLive={focusedTaskLive} />;
}
