import { SegmentedControl, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { memo, useState } from 'react';
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
            key={focusedTaskSnap.id}
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
              const blankDefinition: IFeatureTaskDefinition = {
                kind: TaskDefinitionKinds.Feature,
                label: '',
                description: '',
              };

              focusedTaskLive.definition = blankDefinition;

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

function RawFilledFocusedFeatureTaskView(props: {
  readonly focusedTaskLive: IEditedTask;
  readonly definition: IFeatureTaskDefinition;
}) {
  const { focusedTaskLive, definition } = props;

  const [label, setLabel] = useState(definition.label);
  const [description, setDescription] = useState(definition.description);

  return (
    <Stack className={classes.stack}>
      <TextInput
        label="Label"
        placeholder="short title"
        value={label}
        onChange={(event) => {
          setLabel(event.currentTarget.value);

          focusedTaskLive.definition = {
            kind: TaskDefinitionKinds.Feature,
            label: event.currentTarget.value,
            description,
          };
        }}
      />
      <Textarea
        label="Description"
        placeholder="task description"
        value={description}
        onChange={(event) => {
          setDescription(event.currentTarget.value);

          focusedTaskLive.definition = {
            kind: TaskDefinitionKinds.Feature,
            label,
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

const FilledFocusedFeatureTaskView = memo(RawFilledFocusedFeatureTaskView);

function FilledFocusedMergeTaskView() {
  return (
    <div className={classes.mergeRoot}>
      <Text c="dimmed">Merge tasks cannot be edited</Text>
    </div>
  );
}
