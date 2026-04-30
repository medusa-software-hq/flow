import { type Edge, type NodeTypes } from '@xyflow/react';
import { useSnapshot } from 'valtio';
import { type TTaskId } from '@/app/session/edited_session/CEditedTask';
import type { TAnySession } from '@/app/session/ISession';
import { TaskNode, taskNodeTag } from '../../components/SessionCanvas/TaskNode';

export type MyNode = TaskNode;

export const myNodeTypes: NodeTypes = {
  task: TaskNode,
};

export type MyEdge = Edge<{
  readonly sourceTaskId: TTaskId;
  readonly targetTaskId: TTaskId;
}>;

const taskNodeIdPrefix = 'task-';

export function buildTaskNodeId(taskId: TTaskId): string {
  return `${taskNodeIdPrefix}${taskId.toString()}`;
}

export function parseTaskNodeId(nodeId: string): TTaskId | null {
  if (nodeId.startsWith(taskNodeIdPrefix)) {
    const taskIdStr = nodeId.slice(taskNodeIdPrefix.length);

    try {
      return BigInt(taskIdStr);
    } catch {
      throw new Error(`Invalid task node ID: ${nodeId}`);
    }
  } else {
    return null;
  }
}

export interface UseMyFlowGraphArgs {
  readonly sessionLive: TAnySession;
  readonly selectedNodeIds: ReadonlySet<string>;
  readonly selectedEdgeIds: ReadonlySet<string>;
}

export function useMyFlowGraph(args: UseMyFlowGraphArgs): readonly [MyNode[], MyEdge[]] {
  const { sessionLive, selectedNodeIds, selectedEdgeIds } = args;
  const sessionSnap: TAnySession = useSnapshot(sessionLive);

  void sessionSnap.stamp;

  const nodes: MyNode[] = [];
  const edges: MyEdge[] = [];

  for (const [taskId, taskSnap] of sessionSnap.taskById.entries()) {
    const taskLive = sessionLive.getTaskById(taskId);

    if (taskLive === null) {
      throw new Error(`Task with ID ${String(taskId)} not found`);
    }

    const taskNodeId = buildTaskNodeId(taskId);

    const taskNode: TaskNode = {
      type: taskNodeTag,
      id: taskNodeId,
      position: taskSnap.position,
      data: { taskId, taskLive },
      selected: selectedNodeIds.has(taskNodeId),
    };

    nodes.push(taskNode);

    const sourceTasks = sessionSnap.getSourceTasks(taskId);

    sourceTasks.forEach((sourceTask) => {
      const sourceTaskId = sourceTask.id;
      const sourceTaskNodeId = buildTaskNodeId(sourceTaskId);

      const sourceTaskSnap = sessionSnap.taskById.get(sourceTaskId);

      if (sourceTaskSnap === undefined) {
        throw new Error(`Source task with ID ${String(sourceTaskId)} not found`);
      }

      const connectionEdgeId = `task-${sourceTaskId.toString()}-to-task-${taskId.toString()}`;

      const connectionEdge: MyEdge = {
        id: connectionEdgeId,
        data: {
          sourceTaskId: sourceTaskId,
          targetTaskId: taskId,
        },
        source: sourceTaskNodeId,
        target: taskNodeId,
        selected: selectedEdgeIds.has(connectionEdgeId),
      };

      edges.push(connectionEdge);
    });
  }

  return [nodes, edges];
}
