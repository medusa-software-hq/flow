import type { Client } from '@connectrpc/connect';
import { Anchor, Badge, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { useCallback, useEffect, useState } from 'react';
import type { GitHubService, Issue } from './gen/medusa/github/v1/github_service_pb.ts';

export function GitHubIssues({
  client,
  headers,
  onError,
}: {
  client: Client<typeof GitHubService>;
  headers: HeadersInit;
  onError: (err: unknown) => void;
}) {
  const [issues, setIssues] = useState<Issue[] | null>(null);

  const handleError = useCallback(
    (err: unknown) => {
      onError(err);
    },
    [onError]
  );

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const response = await client.listIssues({}, { headers });
        if (!cancelled) {
          setIssues(response.issues);
        }
      } catch (err: unknown) {
        if (!cancelled) {
          handleError(err);
        }
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [client, headers, handleError]);

  return (
    <Stack gap="sm">
      <Title order={2}>Latest issues</Title>
      {issues === null ? (
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
