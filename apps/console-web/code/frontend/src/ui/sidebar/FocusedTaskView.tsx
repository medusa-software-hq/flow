import './FocusedTaskView.css';

import { useSnapshot } from 'valtio';
import { CTask } from '../../session_editor/CTask.ts';
import type { ITask } from '../../session_editor/ITask';

function EmptyFocusedTaskView() {
  return (
    <div style={{ padding: 16, fontFamily: 'sans-serif' }}>
      <h2>Empty</h2>
      <div></div>
    </div>
  );
}

interface ProperFocusedTaskViewProps {
  readonly focusedTaskLive: CTask;
}

function ProperFocusedTaskView(props: ProperFocusedTaskViewProps) {
  const { focusedTaskLive } = props;
  const focusedTaskLiveSnap: ITask = useSnapshot(focusedTaskLive);

  const label = focusedTaskLiveSnap.label;
  const description = focusedTaskLiveSnap.description;

  return (
    <div className={'property-table-wrapper'}>
      <h2>Task</h2>
      <div className={'property-table'}>
        <div className={'property-name'}>Label</div>
        <div className={'property-value'}>{label}</div>

        <div className={'property-name'}>Description</div>
        <div className={'property-value'}>{description || '—'}</div>
      </div>
    </div>
  );
}

interface FocusedTaskViewProps {
  readonly focusedTaskLive: CTask | null;
}

export function FocusedTaskView(props: FocusedTaskViewProps) {
  const { focusedTaskLive } = props;

  switch (focusedTaskLive) {
    case null:
      return <EmptyFocusedTaskView />;
    default:
      return <ProperFocusedTaskView focusedTaskLive={focusedTaskLive} />;
  }
}
