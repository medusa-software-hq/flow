import { useState } from 'react';
import { ISessionWorkspace } from '@/app/session_workspace/ISessionWorkspace';
import { TTaskId } from '@/app/session_workspace/task_graph/edited/CEditedTask';
import { SessionMainView } from '../AppMainView/SessionMainView';
import { SessionSidebarView } from '../AppSidebarContent/SessionSidebarView';
import classes from './SessionView.module.css';

export interface SessionViewProps {
  readonly sessionWorkspaceLive: ISessionWorkspace;
}

export function SessionView({ sessionWorkspaceLive }: SessionViewProps) {
  const [focusedTaskId, setFocusedTaskId] = useState<TTaskId | null>(null);

  return (
    <div className={classes.shell}>
      <div className={classes.sidebar}>
        <SessionSidebarView
          sessionWorkspaceLive={sessionWorkspaceLive}
          focusedTaskId={focusedTaskId}
        />
      </div>

      <div className={classes.main}>
        <SessionMainView
          sessionWorkspaceLive={sessionWorkspaceLive}
          onTaskFocused={setFocusedTaskId}
        />
      </div>
    </div>
  );
}
