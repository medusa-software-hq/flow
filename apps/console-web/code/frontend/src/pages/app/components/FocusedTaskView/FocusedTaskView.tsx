import { SegmentedControl, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { useSnapshot } from 'valtio';
import { IEditedTask } from '@/app/session_workspace/task_graph/edited/IEditedTask';
import {
  IFeatureTaskDefinition,
  MergeTaskDefinition,
  TaskDefinitionKinds,
} from '@/app/session_workspace/task_graph/ITaskDefinition';
import classes from './FocusedTaskView.module.css';

interface EditedTaskViewProps {
  readonly editedTaskLive: IEditedTask | null;
}

export function FocusedTaskView(props: EditedTaskViewProps) {
  const { editedTaskLive } = props;

  switch (editedTaskLive) {
    case null:
      return <EmptyFocusedTaskView />;
    default:
      return <FilledFocusedTaskView focusedTaskLive={editedTaskLive} />;
  }
}

function EmptyFocusedTaskView() {
  return (
    <div className={classes.empty}>
      <Text c="dimmed">Select a single task</Text>
    </div>
  );
}

function FilledFocusedTaskView(props: { readonly focusedTaskLive: IEditedTask }) {
  const { focusedTaskLive } = props;

  const focusedTaskSnap = useSnapshot(focusedTaskLive);
  const focusedTaskDefinition = focusedTaskSnap.definition;

  const buildContent = () => {
    switch (focusedTaskDefinition.kind) {
      case TaskDefinitionKinds.Feature: {
        return (
          <FilledFocusedFeatureTaskView
            focusedTaskLive={focusedTaskLive}
            definition={focusedTaskDefinition}
          />
        );
      }

      case TaskDefinitionKinds.Merge: {
        return <FilledFocusedMergeTaskView />;
      }
    }
  };

  return (
    <div className={classes.root}>
      <SegmentedControl
        value={focusedTaskDefinition.kind}
        onChange={(newValue) => {
          switch (newValue) {
            case TaskDefinitionKinds.Feature: {
              const newDefinition: IFeatureTaskDefinition = {
                kind: TaskDefinitionKinds.Feature,
                label: '',
                description: '',
              };

              focusedTaskLive.definition = newDefinition;

              break;
            }

            case TaskDefinitionKinds.Merge: {
              focusedTaskLive.definition = MergeTaskDefinition;

              break;
            }
          }
        }}
        data={[
          { label: 'Feature', value: TaskDefinitionKinds.Feature },
          { label: 'Merge', value: TaskDefinitionKinds.Merge },
        ]}
      />
      {buildContent()}
    </div>
  );
}

function FilledFocusedFeatureTaskView(props: {
  readonly focusedTaskLive: IEditedTask;
  readonly definition: IFeatureTaskDefinition;
}) {
  const { focusedTaskLive, definition } = props;

  return (
    <Stack className={classes.stack}>
      <TextInput
        label="Label"
        placeholder="short title"
        value={definition.label}
        onChange={(event) => {
          focusedTaskLive.definition = {
            ...definition,
            label: event.currentTarget.value,
          };
        }}
      />
      <Textarea
        label="Description"
        placeholder="task description"
        value={definition.description}
        onChange={(event) => {
          focusedTaskLive.definition = {
            ...definition,
            description: event.currentTarget.value,
          };
        }}
        autosize
        minRows={8}
        maxRows={24}
      />
    </Stack>
  );
}

function FilledFocusedMergeTaskView() {
  return (
    <div className={classes.mergeRoot}>
      <Text c="dimmed">Merge tasks cannot be edited</Text>
    </div>
  );
}
