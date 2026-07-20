import type { Client } from '@connectrpc/connect';
import { Anchor, Badge, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import type { GitHubService, Issue } from './gen/medusa/github/v1/github_service_pb.ts';

export function GitHubIssues({
  client,
  headers,
  onUnauthorized,
}: {
  client: Client<typeof GitHubService>;
  headers: HeadersInit;
  onUnauthorized: () => void;
}) {
  const [issues, setIssues] = useState<Issue[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const response = await client.listIssues({}, { headers });
        if (!cancelled) {
          setIssues(response.issues);
        }
      } catch (err: unknown) {
        if (cancelled) {
          return;
        }
        const message = err instanceof Error ? err.message : String(err);
        if (message.includes('401') || message.includes('unauthenticated')) {
          onUnauthorized();
        } else {
          setError(message);
        }
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [client, headers, onUnauthorized]);

  return (
    <Stack gap="sm">
      <Title order={2}>Latest issues</Title>
      {error !== null ? (
        <Text c="red" size="sm">
          Failed to load issues: {error}
        </Text>
      ) : issues === null ? (
        <Loader size="sm" />
      ) : issues.length === 0 ? (
        <Text c="dimmed">No issues found.</Text>
      ) : (
        <Stack gap="xs">
          {issues.map((issue) => (
            <Group key={issue.number} gap="xs" wrap="nowrap" align="center">
              <Badge color={issue.state === 'open' ? 'green' : 'gray'} variant="light">
                {issue.state}
              </Badge>
              <Anchor href={issue.url} target="_blank" rel="noreferrer" lineClamp={1}>
                #{issue.number} {issue.title}
              </Anchor>
              {issue.author !== '' && (
                <Text c="dimmed" size="sm">
                  by {issue.author}
                </Text>
              )}
            </Group>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
