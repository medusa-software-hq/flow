import { Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useSnapshot } from 'valtio';
import type { IEditedTask, ITask } from '@/app/session/ITask';
import classes from './FocusedTaskView.module.css';

function EmptyFocusedTaskView() {
  return (
    <div className={classes.empty}>
      <Text c="dimmed">Select a single task</Text>
    </div>
  );
}

interface ProperFocusedTaskViewProps {
  readonly focusedTaskLive: IEditedTask;
  readonly onChanged: () => void;
}

function ProperFocusedTaskView(props: ProperFocusedTaskViewProps) {
  const { focusedTaskLive, onChanged } = props;
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
          onChanged();
        }}
      />
      <Textarea
        label="Description"
        placeholder="task description"
        value={description}
        onChange={(event) => {
          focusedTaskLive.description = event.currentTarget.value;
          onChanged();
        }}
        autosize
        minRows={8}
        maxRows={24}
      />
    </Stack>
  );
}

interface EditedTaskViewProps {
  readonly editedTaskLive: IEditedTask | null;
  readonly onChanged: () => void;
}

export function EditedTaskView(props: EditedTaskViewProps) {
  const { editedTaskLive, onChanged } = props;

  switch (editedTaskLive) {
    case null:
      return <EmptyFocusedTaskView />;
    default:
      return <ProperFocusedTaskView focusedTaskLive={editedTaskLive} onChanged={onChanged} />;
  }
}
