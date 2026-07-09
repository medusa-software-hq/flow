import type { Client } from '@connectrpc/connect';
import { Alert, Button, Loader, Select, Stack, Text, Textarea, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import type { GitHubService } from './gen/medusa/github/v1/github_service_pb.ts';
import type { SessionService } from './gen/medusa/session/v1/session_service_pb.ts';

/** Pre-fill values passed via navigation state by "Retry as new session" on the detail page. */
interface PrefillState {
  repoFullName?: string;
  taskMarkdown?: string;
}

function reportError(
  err: unknown,
  onUnauthorized: () => void,
  setError: (message: string) => void
) {
  const message = err instanceof Error ? err.message : String(err);
  if (message.includes('401') || message.includes('unauthenticated')) {
    onUnauthorized();
  } else {
    setError(message);
  }
}

export function NewSessionForm({
  gitHubClient,
  sessionClient,
  headers,
  onUnauthorized,
}: {
  gitHubClient: Client<typeof GitHubService>;
  sessionClient: Client<typeof SessionService>;
  headers: HeadersInit;
  onUnauthorized: () => void;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const prefill = location.state as PrefillState | null;

  const [repoOptions, setRepoOptions] = useState<{ value: string; label: string }[] | null>(null);
  const [repoLoadError, setRepoLoadError] = useState<string | null>(null);

  const [repoFullName, setRepoFullName] = useState<string | null>(prefill?.repoFullName ?? null);
  const [taskMarkdown, setTaskMarkdown] = useState(prefill?.taskMarkdown ?? '');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const response = await gitHubClient.listRepositories({}, { headers });
        if (!cancelled) {
          setRepoOptions(
            response.repositories.map((repo) => ({ value: repo.fullName, label: repo.fullName }))
          );
        }
      } catch (err: unknown) {
        if (!cancelled) {
          reportError(err, onUnauthorized, setRepoLoadError);
        }
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [gitHubClient, headers, onUnauthorized]);

  const repoMissing = repoFullName === null || repoFullName === '';
  const taskMissing = taskMarkdown.trim() === '';

  async function handleSubmit() {
    setTouched(true);
    if (repoMissing || taskMissing) {
      return;
    }

    setSubmitting(true);
    setSubmitError(null);

    try {
      const response = await sessionClient.createSession(
        { repoFullName: repoFullName, taskMarkdown },
        { headers }
      );
      await navigate(`/sessions/${response.session?.id}`);
    } catch (err: unknown) {
      reportError(err, onUnauthorized, setSubmitError);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Stack gap="md" maw={600}>
      <Title order={1}>New session</Title>

      <Text c="dimmed" size="sm">
        The target repository must contain a Flow <code>project.yaml</code> and be green
        (bootstrap/analyze/test) on its default branch — sessions on repos that don&apos;t meet this
        will fail at the initial health check.
      </Text>

      {repoLoadError !== null && (
        <Alert color="red" title="Failed to load repositories">
          {repoLoadError}
        </Alert>
      )}

      <Select
        label="Repository"
        placeholder={repoOptions === null ? 'Loading…' : 'Select a repository'}
        data={repoOptions ?? []}
        value={repoFullName}
        onChange={setRepoFullName}
        searchable
        disabled={repoOptions === null}
        rightSection={repoOptions === null ? <Loader size="xs" /> : undefined}
        error={touched && repoMissing ? 'Select a repository' : undefined}
        required
      />

      <Textarea
        label="Task"
        description="Markdown"
        placeholder="Describe what should be done…"
        value={taskMarkdown}
        onChange={(event) => setTaskMarkdown(event.currentTarget.value)}
        rows={8}
        error={touched && taskMissing ? 'Describe the task' : undefined}
        required
      />

      {submitError !== null && (
        <Alert color="red" title="Failed to create session">
          {submitError}
        </Alert>
      )}

      <Button onClick={() => void handleSubmit()} loading={submitting} w="fit-content">
        Create session
      </Button>
    </Stack>
  );
}
