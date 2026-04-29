import { Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useSnapshot } from 'valtio';
import { CTask } from '@/session_editor/CTask';
import type { ITask } from '@/session_editor/ITask';
import classes from './FocusedTaskView.module.css';

function EmptyFocusedTaskView() {
  return (
    <div className={classes.empty}>
      <Text c="dimmed">Select a single task</Text>
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
    <Stack className={classes.stack}>
      <TextInput
        label="Label"
        placeholder="short title"
        value={label}
        onChange={(event) => {
          focusedTaskLive.label = event.currentTarget.value;
        }}
      />
      <Textarea
        label="Description"
        placeholder="task description"
        value={description}
        onChange={(event) => {
          focusedTaskLive.description = event.currentTarget.value;
        }}
        autosize
        minRows={8}
        maxRows={24}
      />
    </Stack>
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
