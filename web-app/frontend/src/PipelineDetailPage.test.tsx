import { create } from '@bufbuild/protobuf';
import { timestampFromDate } from '@bufbuild/protobuf/wkt';
import { render, screen, userEvent } from '@test-utils';
import { MemoryRouter, Route, Routes } from 'react-router';
import {
  IssuePipelineSchema,
  IssuePipelineState,
} from './gen/medusa/pipeline/v1/pipeline_service_pb.ts';
import { Engine, SessionSchema, SessionState } from './gen/medusa/session/v1/session_service_pb.ts';
import { PipelineDetailPage } from './PipelineDetailPage.tsx';

type PipelineClient = Parameters<typeof PipelineDetailPage>[0]['pipelineClient'];
type SessionClient = Parameters<typeof PipelineDetailPage>[0]['sessionClient'];
type Pipeline = ReturnType<typeof create<typeof IssuePipelineSchema>>;

function pipeline(fields: Partial<Pipeline> = {}): Pipeline {
  return create(IssuePipelineSchema, {
    id: 'p1',
    repoFullName: 'acme/app',
    issueNumber: 5,
    issueTitle: 'Do the thing',
    issueUrl: 'https://github.com/acme/app/issues/5',
    state: IssuePipelineState.IN_PROGRESS,
    sessionId: 'claude-session',
    shadowSessionId: 'builtin-session',
    updatedAt: timestampFromDate(new Date('2026-01-02T03:04:05Z')),
    ...fields,
  });
}

function session(id: string, engine: Engine, taskMarkdown: string) {
  return create(SessionSchema, {
    id,
    repoFullName: 'acme/app',
    taskMarkdown,
    state: SessionState.RUNNING,
    createdBy: 'alice@example.com',
    createdAt: timestampFromDate(new Date('2026-01-01T00:00:00Z')),
    engine,
  });
}

function renderPage(pipelineRow: Pipeline, getSession: SessionClient['getSession']) {
  const pipelineClient = {
    listIssuePipelines: () => Promise.resolve({ pipelines: [pipelineRow] }),
  } as unknown as PipelineClient;
  const sessionClient = { getSession } as unknown as SessionClient;

  return render(
    <MemoryRouter initialEntries={[`/pipelines/${pipelineRow.id}`]}>
      <Routes>
        <Route
          path="/pipelines/:id"
          element={
            <PipelineDetailPage
              pipelineClient={pipelineClient}
              sessionClient={sessionClient}
              headers={{}}
              onUnauthorized={() => {}}
              refreshIntervalMs={100_000}
              sessionPollIntervalMs={10}
            />
          }
        />
      </Routes>
    </MemoryRouter>
  );
}

test('renders a Claude and a Built-in tab, each showing its own engine session', async () => {
  const user = userEvent.setup();
  const getSession = vi.fn(({ id }: { id: string }) => {
    if (id === 'claude-session') {
      return Promise.resolve({
        session: session('claude-session', Engine.CLAUDE, '# Claude task'),
        events: [],
      });
    }
    return Promise.resolve({
      session: session('builtin-session', Engine.BUILTIN, '# Builtin task'),
      events: [],
    });
  });

  renderPage(pipeline(), getSession);

  expect(await screen.findByText('acme/app')).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Claude' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Built-in' })).toBeInTheDocument();

  // The Claude tab is active by default.
  expect(await screen.findByRole('heading', { name: 'Claude task' })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: 'Builtin task' })).toBeNull();

  // Switching tabs reveals the Built-in engine's own, independently-polled session.
  await user.click(screen.getByRole('tab', { name: 'Built-in' }));
  expect(await screen.findByRole('heading', { name: 'Builtin task' })).toBeInTheDocument();
});

test('omits the Built-in tab when the pipeline predates dual-engine fan-out', async () => {
  const getSession = vi.fn(() =>
    Promise.resolve({
      session: session('claude-session', Engine.CLAUDE, '# Claude task'),
      events: [],
    })
  );

  renderPage(pipeline({ shadowSessionId: '' }), getSession);

  expect(await screen.findByRole('heading', { name: 'Claude task' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Claude' })).toBeInTheDocument();
  expect(screen.queryByRole('tab', { name: 'Built-in' })).toBeNull();
});

test('shows a not-found alert when no pipeline matches the id', async () => {
  const pipelineClient = {
    listIssuePipelines: () => Promise.resolve({ pipelines: [] }),
  } as unknown as PipelineClient;
  const sessionClient = { getSession: vi.fn() } as unknown as SessionClient;

  render(
    <MemoryRouter initialEntries={['/pipelines/missing']}>
      <Routes>
        <Route
          path="/pipelines/:id"
          element={
            <PipelineDetailPage
              pipelineClient={pipelineClient}
              sessionClient={sessionClient}
              headers={{}}
              onUnauthorized={() => {}}
            />
          }
        />
      </Routes>
    </MemoryRouter>
  );

  expect(await screen.findByText('Pipeline not found')).toBeInTheDocument();
});
