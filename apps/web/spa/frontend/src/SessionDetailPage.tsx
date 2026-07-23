import type { Client } from '@connectrpc/connect';
import { Anchor, Badge, Button, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import type { Session, SessionService } from './gen/medusa/session/v1/session_service_pb.ts';
import {
  engineColor,
  engineLabel,
  formatTimestamp,
  isSessionActive,
  sessionStateColor,
  sessionStateLabel,
} from './sessionDisplay.ts';
import { SessionPanel } from './SessionPanel.tsx';

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

  const [session, setSession] = useState<Session | null>(null);
  const [aborting, setAborting] = useState(false);

  // The "Stop": abort a running session. The response carries the now-ABORTED session, so the badge
  // flips immediately; the worker learns via its next heartbeat and kills the engine.
  async function abortSession() {
    if (!id) {
      return;
    }
    setAborting(true);
    try {
      const response = await client.abortSession({ id }, { headers });
      if (response.session) {
        setSession(response.session);
      }
    } finally {
      setAborting(false);
    }
  }

  if (!id) {
    return <Loader size="sm" />;
  }

  return (
    <Stack gap="lg" maw={800}>
      {session && (
        <Stack gap="xs">
          <Group justify="space-between" align="flex-start">
            <Title order={1}>{session.repoFullName}</Title>
            <Group gap="xs">
              {isSessionActive(session.state) && (
                <Button
                  color="orange"
                  variant="light"
                  size="xs"
                  loading={aborting}
                  onClick={() => void abortSession()}
                >
                  Stop
                </Button>
              )}
              <Badge color={engineColor[session.engine]} variant="light" size="lg">
                {engineLabel[session.engine]}
              </Badge>
              <Badge color={sessionStateColor[session.state]} variant="light" size="lg">
                {sessionStateLabel[session.state]}
              </Badge>
            </Group>
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
        </Stack>
      )}

      <SessionPanel
        client={client}
        headers={headers}
        onUnauthorized={onUnauthorized}
        sessionId={id}
        pollIntervalMs={pollIntervalMs}
        onSessionChange={setSession}
      />
    </Stack>
  );
}
