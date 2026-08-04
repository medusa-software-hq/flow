import type { Client } from '@connectrpc/connect';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  Paper,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useCallback, useEffect, useRef, useState } from 'react';
import Markdown from 'react-markdown';
import { Link, useNavigate } from 'react-router';
import {
  IssuePipelineState,
  type IssuePipeline,
  type PipelineService,
} from './gen/medusa/pipeline/v1/pipeline_service_pb.ts';
import { isAwaitingGitHub, pipelineStateColor, pipelineStateLabel } from './pipelineDisplay.ts';
import { formatTimestamp } from './sessionDisplay.ts';

const REFRESH_INTERVAL_MS = 7000;

/** Preserves the server's newest-first order while grouping consecutive rows by repo. */
function groupByRepo(pipelines: IssuePipeline[]): [string, IssuePipeline[]][] {
  const groups = new Map<string, IssuePipeline[]>();
  for (const pipeline of pipelines) {
    const existing = groups.get(pipeline.repoFullName);
    if (existing) {
      existing.push(pipeline);
    } else {
      groups.set(pipeline.repoFullName, [pipeline]);
    }
  }
  return [...groups.entries()];
}

export function PipelinesListPage({
  client,
  headers,
  onUnauthorized,
}: {
  client: Client<typeof PipelineService>;
  headers: HeadersInit;
  onUnauthorized: () => void;
}) {
  const [pipelines, setPipelines] = useState<IssuePipeline[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  // The FAILED pipeline the user is confirming a clear for (drives the modal), plus its in-flight
  // state.
  const [clearTarget, setClearTarget] = useState<IssuePipeline | null>(null);
  const [clearing, setClearing] = useState(false);
  const [clearError, setClearError] = useState<string | null>(null);

  const onUnauthorizedRef = useRef(onUnauthorized);
  onUnauthorizedRef.current = onUnauthorized;

  const load = useCallback(async () => {
    try {
      const response = await client.listIssuePipelines({}, { headers });
      setPipelines(response.pipelines);
      setError(null);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      if (message.includes('401') || message.includes('unauthenticated')) {
        onUnauthorizedRef.current();
      } else {
        setError(message);
      }
    }
  }, [client, headers]);

  useEffect(() => {
    let cancelled = false;
    const tick = () => {
      if (!cancelled) {
        void load();
      }
    };
    tick();
    const interval = setInterval(tick, REFRESH_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [load]);

  async function confirmClear() {
    if (!clearTarget) {
      return;
    }
    setClearing(true);
    setClearError(null);
    try {
      await client.clearIssuePipeline({ id: clearTarget.id }, { headers });
      setClearTarget(null);
      await load();
    } catch (err: unknown) {
      setClearError(err instanceof Error ? err.message : String(err));
    } finally {
      setClearing(false);
    }
  }

  return (
    <Stack gap="md">
      <Title order={1}>Pipelines</Title>

      {error !== null ? (
        <Text c="red" size="sm">
          Failed to load pipelines: {error}
        </Text>
      ) : pipelines === null ? (
        <Loader size="sm" />
      ) : pipelines.length === 0 ? (
        <Text c="dimmed">No issue pipelines yet.</Text>
      ) : (
        groupByRepo(pipelines).map(([repo, rows]) => {
          const awaiting = rows.find((row) => isAwaitingGitHub(row.state));
          return (
            <Stack key={repo} gap="xs">
              <Group gap="sm">
                <Title order={3}>{repo}</Title>
                {awaiting && (
                  <Text c="dimmed" size="sm">
                    Waiting on PR review / merge checks for #{awaiting.issueNumber}
                  </Text>
                )}
              </Group>
              <Table>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Issue</Table.Th>
                    <Table.Th>State</Table.Th>
                    <Table.Th>Session</Table.Th>
                    <Table.Th>Pull request</Table.Th>
                    <Table.Th>Updated</Table.Th>
                    <Table.Th />
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((row) => (
                    <PipelineRow key={row.id} row={row} onClear={() => setClearTarget(row)} />
                  ))}
                </Table.Tbody>
              </Table>
            </Stack>
          );
        })
      )}

      <Modal
        opened={clearTarget !== null}
        onClose={() => {
          if (!clearing) {
            setClearTarget(null);
          }
        }}
        title="Clear failed pipeline?"
      >
        <Stack gap="md">
          <Text size="sm">
            While this pipeline is <strong>FAILED</strong> the repository is stopped — no other
            issue in <strong>{clearTarget?.repoFullName}</strong> will be picked up. Clearing it
            releases the repository and makes issue #{clearTarget?.issueNumber} eligible to be
            picked again on the next reconcile.
          </Text>
          {clearError !== null && (
            <Alert color="red" title="Clear failed">
              {clearError}
            </Alert>
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setClearTarget(null)} disabled={clearing}>
              Cancel
            </Button>
            <Button color="red" onClick={() => void confirmClear()} loading={clearing}>
              Clear pipeline
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function PipelineRow({ row, onClear }: { row: IssuePipeline; onClear: () => void }) {
  const navigate = useNavigate();
  const isFailed = row.state === IssuePipelineState.FAILED && !row.cleared;

  return (
    <>
      <Table.Tr onClick={() => void navigate(`/pipelines/${row.id}`)} style={{ cursor: 'pointer' }}>
        <Table.Td>
          <Anchor
            href={row.issueUrl}
            target="_blank"
            rel="noreferrer"
            onClick={(event) => event.stopPropagation()}
          >
            #{row.issueNumber} {row.issueTitle}
          </Anchor>
        </Table.Td>
        <Table.Td>
          <Group gap="xs">
            <Badge color={pipelineStateColor[row.state]} variant="light">
              {pipelineStateLabel[row.state]}
            </Badge>
            {row.cleared && (
              <Badge color="gray" variant="outline">
                Cleared
              </Badge>
            )}
            {row.outboxStuck && (
              <Badge color="orange" variant="filled">
                GitHub sync issue
              </Badge>
            )}
          </Group>
        </Table.Td>
        <Table.Td>
          {row.sessionId !== '' && (
            <Anchor
              component={Link}
              to={`/sessions/${row.sessionId}`}
              onClick={(event) => event.stopPropagation()}
            >
              View session
            </Anchor>
          )}
        </Table.Td>
        <Table.Td>
          {row.prUrl !== '' && (
            <Anchor
              href={row.prUrl}
              target="_blank"
              rel="noreferrer"
              onClick={(event) => event.stopPropagation()}
            >
              View PR
            </Anchor>
          )}
        </Table.Td>
        <Table.Td>{formatTimestamp(row.updatedAt)}</Table.Td>
        <Table.Td>
          {isFailed && (
            <Button
              size="xs"
              color="red"
              variant="light"
              onClick={(event) => {
                event.stopPropagation();
                onClear();
              }}
            >
              Clear
            </Button>
          )}
        </Table.Td>
      </Table.Tr>
      {isFailed && row.failureSummary !== '' && (
        <Table.Tr>
          <Table.Td colSpan={6}>
            <Paper withBorder p="sm" bg="var(--mantine-color-red-light)">
              <Text size="xs" c="dimmed" mb={4}>
                Failure summary
              </Text>
              <div className="failure-summary">
                <Markdown>{row.failureSummary}</Markdown>
              </div>
            </Paper>
          </Table.Td>
        </Table.Tr>
      )}
    </>
  );
}
