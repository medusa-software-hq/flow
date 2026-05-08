import { SegmentedControl, Stack, Text, Textarea, TextInput } from '@mantine/core';
import React, { memo, useState } from 'react';
import { useSnapshot } from 'valtio';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import { UTask } from '@/app/session_workspace/task_graph/ITask';
import {
  BlankTaskDefinition,
  IFeatureTaskDefinition,
  MergeTaskDefinition,
  TaskDefinitionKinds,
  TTaskDefinitionKind,
} from '@/app/session_workspace/task_graph/ITaskDefinition';
import classes from './FocusedTaskView.module.css';

export function FocusedTaskView(props: { readonly focusedTaskLive: UTask }) {
  const { focusedTaskLive } = props;

  const focusedTaskSnap = useSnapshot(focusedTaskLive);
  const focusedTaskDefinition = focusedTaskSnap.definition;

  const onKindChange = (() => {
    switch (focusedTaskLive.kind) {
      case SessionWorkspaceStateKinds.Editing: {
        return (newValue: TTaskDefinitionKind) => {
          switch (newValue) {
            case TaskDefinitionKinds.Blank: {
              focusedTaskLive.definition = BlankTaskDefinition;

              break;
            }

            case TaskDefinitionKinds.Feature: {
              const featureDefinition: IFeatureTaskDefinition = {
                kind: TaskDefinitionKinds.Feature,
                label: '',
                description: '',
              };

              focusedTaskLive.definition = featureDefinition;

              break;
            }

            case TaskDefinitionKinds.Merge: {
              focusedTaskLive.definition = MergeTaskDefinition;

              break;
            }
          }
        };
      }

      case SessionWorkspaceStateKinds.Running: {
        return undefined;
      }
    }
  })();

  const buildContent = () => {
    switch (focusedTaskDefinition.kind) {
      case TaskDefinitionKinds.Feature: {
        return (
          <FocusedFeatureTaskView
            key={focusedTaskSnap.id}
            focusedTaskDefinition={focusedTaskDefinition}
            focusedTaskLive={focusedTaskLive}
          />
        );
      }

      case TaskDefinitionKinds.Blank: {
        return <FilledFocusedBlankTaskView />;
      }

      case TaskDefinitionKinds.Merge: {
        return <FocusedMergeTaskView />;
      }
    }
  };

  return (
    <div className={classes.root}>
      <SegmentedControl
        value={focusedTaskDefinition.kind}
        onChange={onKindChange}
        disabled={onKindChange === undefined}
        data={[
          { label: 'Blank', value: TaskDefinitionKinds.Blank },
          { label: 'Feature', value: TaskDefinitionKinds.Feature },
          { label: 'Merge', value: TaskDefinitionKinds.Merge },
        ]}
      />
      {buildContent()}
    </div>
  );
}

function RawFilledFocusedFeatureTaskView(props: {
  readonly focusedTaskDefinition: IFeatureTaskDefinition;
  readonly focusedTaskLive: UTask;
}) {
  const { focusedTaskDefinition, focusedTaskLive } = props;

  const [label, setLabel] = useState(focusedTaskDefinition.label);
  const [description, setDescription] = useState(focusedTaskDefinition.description);

  const onLabelChange = (() => {
    switch (focusedTaskLive.kind) {
      case SessionWorkspaceStateKinds.Editing: {
        return (event: React.ChangeEvent<HTMLInputElement>) => {
          setLabel(event.currentTarget.value);

          focusedTaskLive.definition = {
            kind: TaskDefinitionKinds.Feature,
            label: event.currentTarget.value,
            description,
          };
        };
      }

      case SessionWorkspaceStateKinds.Running: {
        return undefined;
      }
    }
  })();

  const onDescriptionChange = (() => {
    switch (focusedTaskLive.kind) {
      case SessionWorkspaceStateKinds.Editing: {
        return (event: React.ChangeEvent<HTMLTextAreaElement>) => {
          setDescription(event.currentTarget.value);

          focusedTaskLive.definition = {
            kind: TaskDefinitionKinds.Feature,
            label,
            description: event.currentTarget.value,
          };
        };
      }

      case SessionWorkspaceStateKinds.Running: {
        return undefined;
      }
    }
  })();

  return (
    <Stack className={classes.stack}>
      <TextInput
        label="Label"
        placeholder="short title"
        value={label}
        onChange={onLabelChange}
        readOnly={onLabelChange === undefined}
      />
      <Textarea
        label="Description"
        placeholder="task description"
        value={description}
        onChange={onDescriptionChange}
        readOnly={onDescriptionChange === undefined}
        autosize
        minRows={8}
        maxRows={24}
      />
    </Stack>
  );
}

const FocusedFeatureTaskView = memo(RawFilledFocusedFeatureTaskView);

function FilledFocusedBlankTaskView() {
  return (
    <div className={classes.infoRoot}>
      <Text fw={500}>Blank task</Text>
      <Text c="dimmed">
        This helper node intentionally does nothing. Use it to organize the graph, split flows, or
        leave yourself a visual waypoint.
      </Text>
    </div>
  );
}

function FocusedMergeTaskView() {
  return (
    <div className={classes.infoRoot}>
      <Text c="dimmed">Merge tasks cannot be edited</Text>
    </div>
  );
}
