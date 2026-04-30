import { Text } from '@mantine/core';
import { Handle, type Node, type NodeProps, Position } from '@xyflow/react';
import { memo } from 'react';
import { useSnapshot } from 'valtio';
import type { TTaskId } from '@/app/session/edited_session/CEditedTask';
import { UTask } from '@/app/session/ITask';
import { SessionWorkspaceStateKinds } from '@/app/SessionWorkspaceStateKinds';

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

  const buildLabelText = () => {
    const label = taskSnap.label;

    if (label === '') {
      return <Text c="dimmed">(untitled)</Text>;
    }

    return <Text>{label}</Text>;
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

interface TaskStatusProps {
  readonly taskLive: UTask;
}

function TaskStatus({ taskLive }: TaskStatusProps) {
  const taskSnap: UTask = useSnapshot(taskLive);

  void taskSnap.kind;

  switch (taskLive.kind) {
    case SessionWorkspaceStateKinds.Editing: {
      return <Text>{taskLive.editedTaskLabel}</Text>;
    }

    case SessionWorkspaceStateKinds.Running: {
      return <Text>Progress: {taskLive.getProgress()}</Text>;
    }
  }
}
