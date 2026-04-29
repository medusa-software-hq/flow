import { Text } from '@mantine/core';
import { Handle, type Node, type NodeProps, Position } from '@xyflow/react';
import { memo } from 'react';
import { useSnapshot } from 'valtio';
import type { CTask, TTaskId } from '@/session_editor/CTask';

export const taskNodeTag = 'task' as const;

export type TaskNode = Node<
  {
    readonly taskId: TTaskId;
    readonly taskLive: CTask;
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
      <Handle type="source" position={Position.Right} isConnectable={props.isConnectable} />
    </div>
  );
}

export const TaskNode = memo(RawTaskNode);
