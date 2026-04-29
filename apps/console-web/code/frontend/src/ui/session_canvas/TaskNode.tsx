import { Handle, type Node, type NodeProps, Position } from '@xyflow/react';
import type { CTask, TTaskId } from '../../session_editor/CTask.ts';
import { useSnapshot } from 'valtio';
import { memo } from 'react';

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

  return (
    <div className={'custom-node'}>
      <Handle
        type="target"
        position={Position.Left}
        isConnectable={props.isConnectable}
      />
      <p>{taskSnap.label}</p>
      <Handle
        type="source"
        position={Position.Right}
        isConnectable={props.isConnectable}
      />
    </div>
  );
}

export const TaskNode = memo(RawTaskNode);
