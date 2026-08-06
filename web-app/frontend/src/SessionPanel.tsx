import type { Client } from '@connectrpc/connect';
import {
  Alert,
  Anchor,
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
import { useNavigate } from 'react-router';
import {
  SessionEventKind,
  SessionState,
  type Session,
  type SessionEvent,
  type SessionService,
} from './gen/medusa/session/v1/session_service_pb.ts';
import {
  formatCostUsd,
  formatTimestamp,
  isSessionActive,
  sessionEventKindLabel,
} from './sessionDisplay.ts';

const DEFAULT_POLL_INTERVAL_MS = 3000;

/**
 * Renders one engine's session run: task, progress feed, completion/failure alert, engine banner,
 * and terminal cost. Owns its own polling, so two of these can run side by side (e.g. the Claude
 * and Built-in tabs of a pipeline) without interfering with each other.
 */
export function SessionPanel({
  client,
  headers,
  onUnauthorized,
  sessionId,
  pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
  onSessionChange,
}: {
  client: Client<typeof SessionService>;
  headers: HeadersInit;
  onUnauthorized: () => void;
  sessionId: string;
  /** Overridable for tests; production callers should use the default. */
  pollIntervalMs?: number;
  /** Reports every successfully fetched session, e.g. so a shared header can show live badges. */
  onSessionChange?: (session: Session) => void;
}) {
  const navigate = useNavigate();

  const [session, setSession] = useState<Session | null>(null);
  const [events, setEvents] = useState<SessionEvent[]>([]);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let timeoutId: ReturnType<typeof setTimeout> | undefined;

    async function poll(afterSeq: number) {
      try {
        const response = await client.getSession({ id: sessionId, afterSeq }, { headers });
        if (cancelled) {
          return;
        }

        setSession(response.session);
        if (response.session) {
          onSessionChange?.(response.session);
        }
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
  }, [client, sessionId, headers, onUnauthorized, pollIntervalMs, onSessionChange]);

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
        No session with id <Code>{sessionId}</Code>.
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

  // Claude-only surfaces: the pinned engine banner and the terminal run cost. Both stay absent for
  // builtin sessions (no banner event, no cost), so their view is unchanged.
  const bannerEvent =
    [...events].reverse().find((event) => event.kind === SessionEventKind.ENGINE_BANNER) ?? null;
  const hasCost = session.totalCostUsd !== undefined;
  const isTerminal = !isSessionActive(session.state);

  // The banner is pinned above and the cost on the terminal line, so keep them out of the progress
  // feed itself. Builtin sessions have neither kind, so their feed is unchanged.
  const feedEvents = events.filter(
    (event) =>
      event.kind !== SessionEventKind.ENGINE_BANNER && event.kind !== SessionEventKind.RUN_COST
  );

  return (
    <Stack gap="lg">
      <Stack gap="xs">
        {bannerEvent && (
          <Paper withBorder p="xs" bg="var(--mantine-color-violet-light)">
            <Markdown>{bannerEvent.message}</Markdown>
          </Paper>
        )}
        {isSessionActive(session.state) && latestEventKind !== null && (
          <Text size="sm">Current phase: {sessionEventKindLabel[latestEventKind]}</Text>
        )}
        {isTerminal && hasCost && (
          <Text size="sm">
            Cost: <strong>{formatCostUsd(session.totalCostUsd)}</strong>
          </Text>
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
          <div className="failure-summary">
            <Markdown>{session.failureSummary}</Markdown>
          </div>
          <Button mt="sm" variant="light" onClick={retryAsNewSession}>
            Retry as new session
          </Button>
        </Alert>
      )}

      <Stack gap="xs">
        <Title order={3}>Progress</Title>
        {feedEvents.length === 0 ? (
          <Text c="dimmed" size="sm">
            {isSessionActive(session.state) ? 'Waiting for the worker…' : 'No events recorded.'}
          </Text>
        ) : (
          <Stack gap="sm">
            {feedEvents.map((event) => {
              // Leader/assistant-engine delegations (M3-11) are the primary unit of this engine's
              // feed: a start (the task headline) and a report (the outcome) bracket each
              // delegation, so both get a distinct tint rather than blending into plain phase/health
              // events.
              const isDelegation =
                event.kind === SessionEventKind.DELEGATION ||
                event.kind === SessionEventKind.DELEGATION_REPORT;
              return (
                <Paper
                  key={event.seq}
                  withBorder
                  p="sm"
                  bg={isDelegation ? 'var(--mantine-color-grape-light)' : undefined}
                >
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
              );
            })}
          </Stack>
        )}
        {/* Conclude the timeline from the terminal state so the feed never dead-ends on a
            phase-start (a FAILED run otherwise reads as still "Publishing…"). The full failure
            detail lives in the Failed alert above; here we just close the timeline. */}
        {isTerminal && (
          <Paper
            withBorder
            p="sm"
            bg={
              session.state === SessionState.COMPLETED
                ? 'var(--mantine-color-green-light)'
                : 'var(--mantine-color-red-light)'
            }
          >
            <Text fw={600} size="sm">
              {session.state === SessionState.COMPLETED
                ? '✓ Completed'
                : `✗ Failed${
                    latestEventKind !== null
                      ? ` during ${sessionEventKindLabel[latestEventKind]}`
                      : ''
                  }`}
            </Text>
          </Paper>
        )}
      </Stack>
    </Stack>
  );
}
