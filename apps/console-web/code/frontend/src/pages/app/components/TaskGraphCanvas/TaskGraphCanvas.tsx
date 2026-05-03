import '@xyflow/react/dist/style.css';
import './ReactFlow.css';
import {
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  type NodeOrigin,
  type OnConnect,
  type OnConnectEnd,
  type OnEdgesChange,
  type OnEdgesDelete,
  type OnNodesChange,
  type OnNodesDelete,
  ReactFlow,
  useReactFlow,
} from '@xyflow/react';
import { memo, useCallback, useEffect, useMemo } from 'react';
import { useSnapshot } from 'valtio';
import { proxySet } from 'valtio/utils';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import type { TTaskId } from '@/app/session_workspace/task_graph/edited/CEditedTask';
import { IEditedTaskGraph } from '@/app/session_workspace/task_graph/edited/IEditedTaskGraph';
import { UAnyTaskGraph } from '@/app/session_workspace/task_graph/ITaskGraph';
import { KeyCodes } from '@/utils/KeyCodes';
import {
  type MyEdge,
  type MyNode,
  myNodeTypes,
  parseTaskNodeId,
  useMyFlowGraph,
} from './myFlowGraph';
import { type TaskNode, taskNodeTag } from './TaskNode';
import classes from './SessionCanvas.module.css';

const nodeOrigin: NodeOrigin = [0.5, 0];

export interface SessionCanvasProps {
  readonly taskGraphLive: UAnyTaskGraph;
  readonly onTaskFocused: (taskId: TTaskId | null) => void;
}

function toEditedSession(session: UAnyTaskGraph): IEditedTaskGraph | null {
  switch (session.kind) {
    case SessionWorkspaceStateKinds.Editing:
      return session;
    default:
      return null;
  }
}

function RawTaskGraphCanvas(props: SessionCanvasProps) {
  const { taskGraphLive, onTaskFocused } = props;

  const { screenToFlowPosition } = useReactFlow();

  const selectedNodeIdsLive: Set<string> = useMemo(() => proxySet(), []);
  const selectedNodeIdsSnap: ReadonlySet<string> = useSnapshot(selectedNodeIdsLive);

  useEffect(() => {
    if (selectedNodeIdsSnap.size === 1) {
      const singleSelectedNodeId = [...selectedNodeIdsSnap][0];
      const singleSelectedTaskId = parseTaskNodeId(singleSelectedNodeId);

      onTaskFocused(singleSelectedTaskId);
    } else {
      onTaskFocused(null);
    }
  }, [onTaskFocused, selectedNodeIdsSnap]);

  const selectedEdgeIdsLive: Set<string> = useMemo(() => proxySet(), []);
  const selectedEdgeIdsSnap: ReadonlySet<string> = useSnapshot(selectedEdgeIdsLive);

  const [nodes, edges] = useMyFlowGraph({
    sessionLive: taskGraphLive,
    selectedNodeIds: selectedNodeIdsSnap,
    selectedEdgeIds: selectedEdgeIdsSnap,
  });

  const onConnect: OnConnect = (connection) => {
    const editedSession = toEditedSession(taskGraphLive);

    if (editedSession === null) {
      return;
    }

    const sourceNodeId = connection.source;

    const sourceTaskId = parseTaskNodeId(sourceNodeId);

    if (sourceTaskId === null) {
      return;
    }

    const targetNodeId = connection.target;

    const targetTaskId = parseTaskNodeId(targetNodeId);

    if (targetTaskId === null) {
      return;
    }

    editedSession.createDependency(sourceTaskId, targetTaskId);
  };

  const onConnectEnd: OnConnectEnd = useCallback(
    (event, connectionState) => {
      const editedSessionLive = toEditedSession(taskGraphLive);

      if (editedSessionLive === null) {
        return;
      }

      if (connectionState.isValid) {
        // Valid connection: dropped on some other handle
      } else {
        // Invalid connection: dropped onto the canvas

        const fromNode = connectionState.fromNode;

        if (fromNode === null) {
          throw new Error('From-node not found');
        }

        const fromHandle = connectionState.fromHandle;

        if (fromHandle === null) {
          throw new Error('From-handle not found');
        }

        const fromNodeType = fromNode.type;

        const fromTaskId = (() => {
          switch (fromNodeType) {
            case taskNodeTag: {
              const taskNode = fromNode as unknown as TaskNode;
              return taskNode.data.taskId;
            }
            default:
              throw new Error(`Unknown source node type: ${String(fromNodeType)}`);
          }
        })();

        const { clientX, clientY } = extractClientPos(event);

        const newNodePosition = screenToFlowPosition({
          x: clientX,
          y: clientY,
        });

        switch (fromHandle.type) {
          case 'source': {
            editedSessionLive.createDependentTask(fromTaskId, newNodePosition);
            break;
          }
          case 'target': {
            editedSessionLive.createDependencyTask(fromTaskId, newNodePosition);
            break;
          }
        }
      }
    },
    [screenToFlowPosition, taskGraphLive]
  );

  const onNodesChange: OnNodesChange<MyNode> = useCallback(
    (changes) => {
      const editedSessionLive = toEditedSession(taskGraphLive);

      for (const change of changes) {
        switch (change.type) {
          case 'add':
            break;
          case 'remove':
            break;
          case 'position': {
            if (editedSessionLive === null) {
              return;
            }

            const taskId = parseTaskNodeId(change.id);

            if (taskId === null) {
              continue;
            }

            const task = editedSessionLive.getTaskById(taskId);

            if (task === null) {
              throw new Error(`Moved task with ID ${String(taskId)} not found`);
            }

            const newPosition = change.position;

            if (newPosition === undefined) {
              throw new Error(
                `Position change for task with ID ${String(taskId)} is missing position`
              );
            }

            task.move(newPosition);

            break;
          }
          case 'select': {
            // ID of node that was/will be selected
            const selectedNodeId = change.id;

            if (change.selected) {
              selectedNodeIdsLive.add(selectedNodeId);
            } else {
              selectedNodeIdsLive.delete(selectedNodeId);
            }

            break;
          }
          default:
            break;
        }
      }
    },
    [selectedNodeIdsLive, taskGraphLive]
  );

  const onEdgesChange: OnEdgesChange<MyEdge> = useCallback(
    (changes) => {
      const editedSessionLive = toEditedSession(taskGraphLive);

      if (editedSessionLive === null) {
        return;
      }

      for (const change of changes) {
        switch (change.type) {
          case 'remove': {
            const removedEdgeId = change.id;

            const removedEdgeData = edges.find((edge) => edge.id === removedEdgeId)?.data;

            if (removedEdgeData === undefined) {
              throw new Error(`Removed edge/data not found: ${removedEdgeId}`);
            }

            editedSessionLive.breakDependency(
              removedEdgeData.sourceTaskId,
              removedEdgeData.targetTaskId
            );

            break;
          }
          case 'select': {
            // ID of edge that was/will be selected
            const selectedChangeId = change.id;

            if (change.selected) {
              selectedEdgeIdsLive.add(selectedChangeId);
            } else {
              selectedEdgeIdsLive.delete(selectedChangeId);
            }

            break;
          }
          case 'add':
            break;
          case 'replace':
            break;
        }
      }
    },
    [edges, selectedEdgeIdsLive, taskGraphLive]
  );

  const onNodesDelete: OnNodesDelete<MyNode> = useCallback(
    (deletedNodes) => {
      const editedSessionLive = toEditedSession(taskGraphLive);

      if (editedSessionLive === null) {
        return;
      }

      deletedNodes.forEach((deletedNode) => {
        const deletedNodeId = deletedNode.id;

        selectedNodeIdsLive.delete(deletedNodeId);

        const deletedTaskId = deletedNode.data.taskId;

        editedSessionLive.deleteTask(deletedTaskId);
      });
    },
    [selectedNodeIdsLive, taskGraphLive]
  );

  const onEdgesDelete: OnEdgesDelete<MyEdge> = useCallback(
    (deletedEdges) => {
      const editedSessionLive = toEditedSession(taskGraphLive);

      if (editedSessionLive === null) {
        return;
      }

      for (const deletedEdge of deletedEdges) {
        const deletedEdgeData = deletedEdge.data;

        const deletedEdgeId = deletedEdge.id;

        if (deletedEdgeData === undefined) {
          throw new Error(`Missing data on edge ${deletedEdgeId}`);
        }

        if (selectedEdgeIdsSnap.has(deletedEdgeId)) {
          selectedEdgeIdsLive.delete(deletedEdgeId);
        }

        editedSessionLive.breakDependency(
          deletedEdgeData.sourceTaskId,
          deletedEdgeData.targetTaskId
        );
      }
    },
    [selectedEdgeIdsSnap, taskGraphLive, selectedEdgeIdsLive]
  );

  return (
    <div className={classes.reactFlowWrapper}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        deleteKeyCode={[KeyCodes.Backspace, KeyCodes.Delete]}
        onNodesChange={onNodesChange}
        onNodesDelete={onNodesDelete}
        onEdgesChange={onEdgesChange}
        onEdgesDelete={onEdgesDelete}
        onConnect={onConnect}
        onConnectEnd={onConnectEnd}
        nodeTypes={myNodeTypes}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        nodeOrigin={nodeOrigin}
      >
        <MiniMap
          pannable
          zoomable
          style={{ background: 'rgba(8, 14, 28, 0.9)' }}
          nodeColor="#8f86ff"
        />
        <Controls position="bottom-right" />
        <Background color="#27324a" gap={24} size={1} variant={BackgroundVariant.Dots} />
      </ReactFlow>
    </div>
  );
}

export const TaskGraphCanvas = memo(RawTaskGraphCanvas);

interface ClientPos {
  readonly clientX: number;
  readonly clientY: number;
}

function extractClientPos(event: TouchEvent | MouseEvent): ClientPos {
  if ('changedTouches' in event) {
    // case: TouchEvent
    return event.changedTouches[0];
  }
  // case: MouseEvent
  return event;
}
