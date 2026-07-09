import { timestampDate } from '@bufbuild/protobuf/wkt';
import type { Client } from '@connectrpc/connect';
import { Anchor, Badge, Button, Group, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import {
  SessionState,
  type Session,
  type SessionService,
} from './gen/medusa/session/v1/session_service_pb.ts';

const REFRESH_INTERVAL_MS = 7000;

const stateLabel: Record<SessionState, string> = {
  [SessionState.UNSPECIFIED]: 'Unknown',
  [SessionState.PENDING]: 'Pending',
  [SessionState.RUNNING]: 'Running',
  [SessionState.COMPLETED]: 'Completed',
  [SessionState.FAILED]: 'Failed',
};

const stateColor: Record<SessionState, string> = {
  [SessionState.UNSPECIFIED]: 'gray',
  [SessionState.PENDING]: 'gray',
  [SessionState.RUNNING]: 'blue',
  [SessionState.COMPLETED]: 'green',
  [SessionState.FAILED]: 'red',
};

function formatCreatedAt(session: Session): string {
  if (!session.createdAt) {
    return '—';
  }
  return timestampDate(session.createdAt).toLocaleString();
}

export function SessionsListPage({
  client,
  headers,
  onUnauthorized,
}: {
  client: Client<typeof SessionService>;
  headers: HeadersInit;
  onUnauthorized: () => void;
}) {
  const [sessions, setSessions] = useState<Session[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  // Kept in a ref so the interval effect doesn't need to depend on (and thus restart on) it.
  const onUnauthorizedRef = useRef(onUnauthorized);
  onUnauthorizedRef.current = onUnauthorized;

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const response = await client.listSessions({}, { headers });
        if (!cancelled) {
          setSessions(response.sessions);
          setError(null);
        }
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
    const interval = setInterval(() => void load(), REFRESH_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [client, headers]);

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Title order={1}>Sessions</Title>
        <Button component={Link} to="/sessions/new">
          New session
        </Button>
      </Group>

      {error !== null ? (
        <Text c="red" size="sm">
          Failed to load sessions: {error}
        </Text>
      ) : sessions === null ? (
        <Loader size="sm" />
      ) : sessions.length === 0 ? (
        <Text c="dimmed">No sessions yet.</Text>
      ) : (
        <Table highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Repository</Table.Th>
              <Table.Th>State</Table.Th>
              <Table.Th>Created</Table.Th>
              <Table.Th>Created by</Table.Th>
              <Table.Th>Pull request</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {sessions.map((session) => (
              <Table.Tr
                key={session.id}
                onClick={() => void navigate(`/sessions/${session.id}`)}
                style={{ cursor: 'pointer' }}
              >
                <Table.Td>{session.repoFullName}</Table.Td>
                <Table.Td>
                  <Badge color={stateColor[session.state]} variant="light">
                    {stateLabel[session.state]}
                  </Badge>
                </Table.Td>
                <Table.Td>{formatCreatedAt(session)}</Table.Td>
                <Table.Td>{session.createdBy}</Table.Td>
                <Table.Td>
                  {session.state === SessionState.COMPLETED && session.prUrl !== '' && (
                    <Anchor
                      href={session.prUrl}
                      target="_blank"
                      rel="noreferrer"
                      onClick={(event) => event.stopPropagation()}
                    >
                      View PR
                    </Anchor>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}
