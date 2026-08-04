import type { Client } from '@connectrpc/connect';
import { Alert, Anchor, Badge, Code, Group, Loader, Stack, Tabs, Title } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router';
import type {
  IssuePipeline,
  PipelineService,
} from './gen/medusa/pipeline/v1/pipeline_service_pb.ts';
import type { SessionService } from './gen/medusa/session/v1/session_service_pb.ts';
import { pipelineStateColor, pipelineStateLabel } from './pipelineDisplay.ts';
import { SessionPanel } from './SessionPanel.tsx';

const DEFAULT_REFRESH_INTERVAL_MS = 7000;

export function PipelineDetailPage({
  pipelineClient,
  sessionClient,
  headers,
  onUnauthorized,
  refreshIntervalMs = DEFAULT_REFRESH_INTERVAL_MS,
  sessionPollIntervalMs,
}: {
  pipelineClient: Client<typeof PipelineService>;
  sessionClient: Client<typeof SessionService>;
  headers: HeadersInit;
  onUnauthorized: () => void;
  /** Overridable for tests; production callers should use the default. */
  refreshIntervalMs?: number;
  /** Passed through to each engine's SessionPanel; overridable for tests. */
  sessionPollIntervalMs?: number;
}) {
  const { id } = useParams<{ id: string }>();

  const [pipeline, setPipeline] = useState<IssuePipeline | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onUnauthorizedRef = useRef(onUnauthorized);
  onUnauthorizedRef.current = onUnauthorized;

  useEffect(() => {
    if (!id) {
      return;
    }

    let cancelled = false;

    async function load() {
      try {
        const response = await pipelineClient.listIssuePipelines({}, { headers });
        if (cancelled) {
          return;
        }
        const match = response.pipelines.find((candidate) => candidate.id === id);
        if (match) {
          setPipeline(match);
          setNotFound(false);
        } else {
          setNotFound(true);
        }
        setError(null);
      } catch (err: unknown) {
        if (cancelled) {
          return;
        }
        const message = err instanceof Error ? err.message : String(err);
        if (message.includes('401') || message.includes('unauthenticated')) {
          onUnauthorizedRef.current();
        } else {
          setError(message);
        }
      }
    }

    void load();
    const interval = setInterval(() => void load(), refreshIntervalMs);

    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [pipelineClient, id, headers, refreshIntervalMs]);

  if (!id) {
    return <Loader size="sm" />;
  }

  if (notFound) {
    return (
      <Alert color="red" title="Pipeline not found">
        No pipeline with id <Code>{id}</Code>.
      </Alert>
    );
  }

  if (error !== null) {
    return (
      <Alert color="red" title="Failed to load pipeline">
        {error}
      </Alert>
    );
  }

  if (!pipeline) {
    return <Loader size="sm" />;
  }

  const hasShadow = pipeline.shadowSessionId !== '';

  return (
    <Stack gap="lg" maw={800}>
      <Stack gap="xs">
        <Group justify="space-between" align="flex-start">
          <Title order={1}>{pipeline.repoFullName}</Title>
          <Badge color={pipelineStateColor[pipeline.state]} variant="light" size="lg">
            {pipelineStateLabel[pipeline.state]}
          </Badge>
        </Group>
        <Anchor href={pipeline.issueUrl} target="_blank" rel="noreferrer">
          <Badge color="grape" variant="light">
            Issue #{pipeline.issueNumber}
          </Badge>
        </Anchor>
      </Stack>

      <Tabs defaultValue="claude">
        <Tabs.List>
          <Tabs.Tab value="claude">Claude</Tabs.Tab>
          {hasShadow && <Tabs.Tab value="builtin">Built-in</Tabs.Tab>}
        </Tabs.List>
        <Tabs.Panel value="claude" pt="md">
          <SessionPanel
            client={sessionClient}
            headers={headers}
            onUnauthorized={onUnauthorized}
            sessionId={pipeline.sessionId}
            pollIntervalMs={sessionPollIntervalMs}
          />
        </Tabs.Panel>
        {hasShadow && (
          <Tabs.Panel value="builtin" pt="md">
            <SessionPanel
              client={sessionClient}
              headers={headers}
              onUnauthorized={onUnauthorized}
              sessionId={pipeline.shadowSessionId}
              pollIntervalMs={sessionPollIntervalMs}
            />
          </Tabs.Panel>
        )}
      </Tabs>
    </Stack>
  );
}
