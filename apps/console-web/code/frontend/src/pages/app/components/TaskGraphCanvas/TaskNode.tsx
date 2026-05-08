import { Text } from '@mantine/core';
import { Handle, type Node, type NodeProps, Position } from '@xyflow/react';
import { JSX, memo } from 'react';
import { useSnapshot } from 'valtio';
import mergeIconUrl from '@/../assets/merge-icon.svg';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import type { TTaskId } from '@/app/session_workspace/task_graph/edited/CEditedTask';
import { UTask } from '@/app/session_workspace/task_graph/ITask';
import { TaskDefinitionKinds } from '@/app/session_workspace/task_graph/ITaskDefinition';
import { IRunningTask } from '@/app/session_workspace/task_graph/running/IRunningTask';
import classes from './TaskNode.module.css';

export const taskNodeTag = 'task' as const;

export type TaskNode = Node<
  {
    readonly taskId: TTaskId;
    readonly taskLive: UTask;
  },
  typeof taskNodeTag
>;

function RawTaskNode(props: NodeProps<TaskNode>) {
  const taskLive = props.data.taskLive;

  const taskSnap = useSnapshot(taskLive);
  const taskDefinitionSnap = taskSnap.definition;

  const buildLabelText = () => {
    switch (taskDefinitionSnap.kind) {
      case TaskDefinitionKinds.Feature: {
        const label = taskDefinitionSnap.label;

        if (label === '') {
          return <Text c="dimmed">(untitled)</Text>;
        }

        return <Text>{label}</Text>;
      }
      // mergeIconUrl
      case TaskDefinitionKinds.Merge: {
        return (
          <div className={classes.mergeLabelWrapper}>
            <img height={16} src={mergeIconUrl} alt="Merge icon" className={classes.mergeIcon} />
            <Text fw="bold">Merge</Text>
          </div>
        );
      }
    }
  };

  return (
    <div className="custom-node">
      <Handle type="target" position={Position.Left} isConnectable={props.isConnectable} />
      {buildLabelText()}
      <TaskStatus taskLive={taskLive} />
      <Handle type="source" position={Position.Right} isConnectable={props.isConnectable} />
    </div>
  );
}

export const TaskNode = memo(RawTaskNode);

function TaskStatus(props: { taskLive: UTask }): JSX.Element {
  const { taskLive } = props;
  const taskSnap: UTask = useSnapshot(props.taskLive);

  void taskSnap.kind;

  switch (taskLive.kind) {
    case SessionWorkspaceStateKinds.Editing: {
      return <ProgressBar progress={0} />;
    }

    case SessionWorkspaceStateKinds.Running: {
      return <RunningTaskStatus runningTaskLive={taskLive} />;
    }
  }
}

function RunningTaskStatus(props: { runningTaskLive: IRunningTask }): JSX.Element {
  const runningTaskSnap: UTask = useSnapshot(props.runningTaskLive);

  return <ProgressBar progress={runningTaskSnap.getProgress()} />;
}

function ProgressBar(props: { progress: number }): JSX.Element {
  const boundedProgress = Math.min(Math.max(props.progress, 0), 1);

  return (
    <div className="task-node-progress" aria-hidden="true">
      <div className="task-node-progress__track">
        <div
          className="task-node-progress__fill"
          style={{ transform: `scaleX(${boundedProgress})` }}
        />
      </div>
    </div>
  );
}
