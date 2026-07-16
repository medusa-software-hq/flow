import type { Client } from '@connectrpc/connect';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Code,
  Group,
  Loader,
  Paper,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { useEffect, useState } from 'react';
import Markdown from 'react-markdown';
import { Link, useNavigate, useParams } from 'react-router';
import {
  SessionState,
  type Session,
  type SessionEvent,
  type SessionService,
} from './gen/medusa/session/v1/session_service_pb.ts';
import {
  formatTimestamp,
  isSessionActive,
  sessionEventKindLabel,
  sessionStateColor,
  sessionStateLabel,
} from './sessionDisplay.ts';

const DEFAULT_POLL_INTERVAL_MS = 3000;

export function SessionDetailPage({
  client,
  headers,
  onUnauthorized,
  pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
}: {
  client: Client<typeof SessionService>;
  headers: HeadersInit;
  onUnauthorized: () => void;
  /** Overridable for tests; production callers should use the default. */
  pollIntervalMs?: number;
}) {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [session, setSession] = useState<Session | null>(null);
  const [events, setEvents] = useState<SessionEvent[]>([]);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) {
      return;
    }

    let cancelled = false;
    let timeoutId: ReturnType<typeof setTimeout> | undefined;

    async function poll(afterSeq: number) {
      try {
        const response = await client.getSession({ id, afterSeq }, { headers });
        if (cancelled) {
          return;
        }

        setSession(response.session);
        if (response.events.length > 0) {
          // Merge by seq, not blind append: if this effect re-subscribes (e.g. the auth token
          // refreshes, changing `headers`), it re-polls from afterSeq 0 and would otherwise append
          // the whole stream a second time, showing every event twice.
          setEvents((prev) => {
            const bySeq = new Map(prev.map((event) => [event.seq, event]));
            for (const event of response.events) {
              bySeq.set(event.seq, event);
            }
            return [...bySeq.values()].sort((a, b) => a.seq - b.seq);
          });
        }
        setError(null);

        const latestSeq =
          response.events.length > 0 ? response.events[response.events.length - 1].seq : afterSeq;

        if (response.session && isSessionActive(response.session.state)) {
          timeoutId = setTimeout(() => void poll(latestSeq), pollIntervalMs);
        }
      } catch (err: unknown) {
        if (cancelled) {
          return;
        }
        const message = err instanceof Error ? err.message : String(err);
        if (message.includes('401') || message.includes('unauthenticated')) {
          onUnauthorized();
        } else if (message.includes('NOT_FOUND') || message.includes('404')) {
          setNotFound(true);
        } else {
          setError(message);
        }
      }
    }

    void poll(0);

    return () => {
      cancelled = true;
      if (timeoutId) {
        clearTimeout(timeoutId);
      }
    };
  }, [client, id, headers, onUnauthorized, pollIntervalMs]);

  function retryAsNewSession() {
    if (!session) {
      return;
    }
    void navigate('/sessions/new', {
      state: { repoFullName: session.repoFullName, taskMarkdown: session.taskMarkdown },
    });
  }

  if (notFound) {
    return (
      <Alert color="red" title="Session not found">
        No session with id <Code>{id}</Code>.
      </Alert>
    );
  }

  if (error !== null) {
    return (
      <Alert color="red" title="Failed to load session">
        {error}
      </Alert>
    );
  }

  if (!session) {
    return <Loader size="sm" />;
  }

  const latestEventKind = events.length > 0 ? events[events.length - 1].kind : null;

  return (
    <Stack gap="lg" maw={800}>
      <Stack gap="xs">
        <Group justify="space-between" align="flex-start">
          <Title order={1}>{session.repoFullName}</Title>
          <Badge color={sessionStateColor[session.state]} variant="light" size="lg">
            {sessionStateLabel[session.state]}
          </Badge>
        </Group>
        <Text c="dimmed" size="sm">
          Created {formatTimestamp(session.createdAt)} by {session.createdBy}
        </Text>
        {session.issueNumber > 0 && (
          <Group gap="xs">
            <Anchor href={session.issueUrl} target="_blank" rel="noreferrer">
              <Badge color="grape" variant="light">
                Issue #{session.issueNumber}
              </Badge>
            </Anchor>
            <Anchor component={Link} to="/pipelines" size="sm">
              View pipeline
            </Anchor>
          </Group>
        )}
        {isSessionActive(session.state) && latestEventKind !== null && (
          <Text size="sm">Current phase: {sessionEventKindLabel[latestEventKind]}</Text>
        )}
      </Stack>

      <Stack gap="xs">
        <Title order={3}>Task</Title>
        <Paper withBorder p="sm">
          <Markdown>{session.taskMarkdown}</Markdown>
        </Paper>
      </Stack>

      {session.state === SessionState.COMPLETED && session.prUrl !== '' && (
        <Alert color="green" title="Completed">
          <Anchor href={session.prUrl} target="_blank" rel="noreferrer" fw={600}>
            View pull request
          </Anchor>
        </Alert>
      )}

      {session.state === SessionState.FAILED && (
        <Alert color="red" title="Failed">
          <Markdown>{session.failureSummary}</Markdown>
          <Button mt="sm" variant="light" onClick={retryAsNewSession}>
            Retry as new session
          </Button>
        </Alert>
      )}

      <Stack gap="xs">
        <Title order={3}>Progress</Title>
        {events.length === 0 ? (
          <Text c="dimmed" size="sm">
            {isSessionActive(session.state) ? 'Waiting for the worker…' : 'No events recorded.'}
          </Text>
        ) : (
          <Stack gap="sm">
            {events.map((event) => (
              <Paper key={event.seq} withBorder p="sm">
                <Group justify="space-between" mb={4}>
                  <Text fw={600} size="sm">
                    {sessionEventKindLabel[event.kind]}
                  </Text>
                  <Text c="dimmed" size="xs">
                    {formatTimestamp(event.createdAt)}
                  </Text>
                </Group>
                <Markdown>{event.message}</Markdown>
              </Paper>
            ))}
          </Stack>
        )}
      </Stack>
    </Stack>
  );
}
