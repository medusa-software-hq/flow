import { ReactFlowProvider } from '@xyflow/react';
import { useSnapshot } from 'valtio';
import { ISessionWorkspace } from '@/app/session_workspace/ISessionWorkspace';
import { USessionWorkspaceState } from '@/app/session_workspace/ISessionWorkspaceState';
import { TTaskId } from '@/app/session_workspace/task_graph/edited/CEditedTask';
import { TaskGraphCanvas } from '../TaskGraphCanvas/TaskGraphCanvas';

export interface SessionMainViewProps {
  readonly sessionWorkspaceLive: ISessionWorkspace;
  readonly onTaskFocused: (taskId: TTaskId | null) => void;
}

export function SessionMainView({ sessionWorkspaceLive, onTaskFocused }: SessionMainViewProps) {
  const sessionWorkspaceSnap = useSnapshot(sessionWorkspaceLive);

  void sessionWorkspaceSnap.currentState;
  const currentSessionWorkspaceStateLive: USessionWorkspaceState =
    sessionWorkspaceLive.currentState;

  const taskGraphLive = currentSessionWorkspaceStateLive.exposedAnyTaskGraph;

  return (
    <ReactFlowProvider>
      <TaskGraphCanvas taskGraphLive={taskGraphLive} onTaskFocused={onTaskFocused} />
    </ReactFlowProvider>
  );
}
