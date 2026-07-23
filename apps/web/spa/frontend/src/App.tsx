import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import { Anchor, Box, Group, Stack } from '@mantine/core';
import { useMemo } from 'react';
import { Link, Navigate, Route, Routes } from 'react-router';
import { GitHubService } from './gen/medusa/github/v1/github_service_pb.ts';
import { PipelineService } from './gen/medusa/pipeline/v1/pipeline_service_pb.ts';
import { SessionService } from './gen/medusa/session/v1/session_service_pb.ts';
import { NewSessionForm } from './NewSessionForm.tsx';
import { PipelineDetailPage } from './PipelineDetailPage.tsx';
import { PipelinesListPage } from './PipelinesListPage.tsx';
import { SessionDetailPage } from './SessionDetailPage.tsx';
import { SessionsListPage } from './SessionsListPage.tsx';
import { SignInWall } from './SignInWall.tsx';
import { useAuth } from './useAuth.tsx';

const API_URL = import.meta.env.VITE_API_URL as string;

if (!API_URL) {
  throw new Error('VITE_API_URL is not set');
}

const transport = createGrpcWebTransport({
  baseUrl: API_URL,
});

const gitHubClient = createClient(GitHubService, transport);
const sessionClient = createClient(SessionService, transport);
const pipelineClient = createClient(PipelineService, transport);

function AuthenticatedApp({ token }: { token: string }) {
  const { handleUnauthorized } = useAuth();
  const headers = useMemo(() => ({ Authorization: `Bearer ${token}` }), [token]);

  return (
    <Stack gap={0}>
      <Group
        component="nav"
        justify="flex-start"
        gap="lg"
        p="md"
        style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}
      >
        <Anchor component={Link} to="/sessions" fw={600}>
          Sessions
        </Anchor>
        <Anchor component={Link} to="/pipelines" fw={600}>
          Pipelines
        </Anchor>
      </Group>

      <Routes>
        <Route path="/" element={<Navigate to="/sessions" replace />} />
        <Route
          path="/sessions"
          element={
            <Box p="md">
              <SessionsListPage
                client={sessionClient}
                headers={headers}
                onUnauthorized={handleUnauthorized}
              />
            </Box>
          }
        />
        <Route
          path="/sessions/new"
          element={
            <Box p="md">
              <NewSessionForm
                gitHubClient={gitHubClient}
                sessionClient={sessionClient}
                headers={headers}
                onUnauthorized={handleUnauthorized}
              />
            </Box>
          }
        />
        <Route
          path="/sessions/:id"
          element={
            <Box p="md">
              <SessionDetailPage
                client={sessionClient}
                headers={headers}
                onUnauthorized={handleUnauthorized}
              />
            </Box>
          }
        />
        <Route
          path="/pipelines"
          element={
            <Box p="md">
              <PipelinesListPage
                client={pipelineClient}
                headers={headers}
                onUnauthorized={handleUnauthorized}
              />
            </Box>
          }
        />
        <Route
          path="/pipelines/:id"
          element={
            <Box p="md">
              <PipelineDetailPage
                pipelineClient={pipelineClient}
                sessionClient={sessionClient}
                headers={headers}
                onUnauthorized={handleUnauthorized}
              />
            </Box>
          }
        />
      </Routes>
    </Stack>
  );
}

function App() {
  const { state } = useAuth();

  if (state.status === 'loading') {
    return null;
  }
  if (state.status === 'unauthenticated') {
    return <SignInWall />;
  }
  return <AuthenticatedApp token={state.token} />;
}

export default App;
