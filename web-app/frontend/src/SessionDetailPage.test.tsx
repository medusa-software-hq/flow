import { create } from '@bufbuild/protobuf';
import { timestampFromDate } from '@bufbuild/protobuf/wkt';
import { render, screen, userEvent } from '@test-utils';
import { MemoryRouter, Route, Routes } from 'react-router';
import {
  Engine,
  SessionEventKind,
  SessionEventSchema,
  SessionSchema,
  SessionState,
} from './gen/medusa/session/v1/session_service_pb.ts';
import { SessionDetailPage } from './SessionDetailPage.tsx';

type SessionClient = Parameters<typeof SessionDetailPage>[0]['client'];

interface SessionOverrides {
  state?: SessionState;
  prUrl?: string;
  failureSummary?: string;
  repoFullName?: string;
  taskMarkdown?: string;
  engine?: Engine;
  totalCostUsd?: number;
}

function baseSession(overrides: SessionOverrides = {}) {
  return create(SessionSchema, {
    id: 'abc',
    repoFullName: 'acme/app',
    taskMarkdown: '# Do the thing',
    state: SessionState.RUNNING,
    createdBy: 'alice@example.com',
    createdAt: timestampFromDate(new Date('2026-01-01T00:00:00Z')),
    engine: Engine.CLAUDE,
    ...overrides,
  });
}

function event(seq: number, kind: SessionEventKind, message: string) {
  return create(SessionEventSchema, {
    seq,
    kind,
    message,
    createdAt: timestampFromDate(new Date('2026-01-01T00:00:00Z')),
  });
}

function renderDetail(getSession: SessionClient['getSession'], pollIntervalMs = 10) {
  const client = { getSession } as unknown as SessionClient;

  return render(
    <MemoryRouter initialEntries={['/sessions/abc']}>
      <Routes>
        <Route
          path="/sessions/:id"
          element={
            <SessionDetailPage
              client={client}
              headers={{}}
              onUnauthorized={() => {}}
              pollIntervalMs={pollIntervalMs}
            />
          }
        />
        <Route path="/sessions/new" element={<div>New session page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

test('renders header, task markdown, and the progress feed', async () => {
  const getSession = vi
    .fn()
    .mockResolvedValueOnce({
      session: baseSession(),
      events: [event(1, SessionEventKind.SCOUTING_ROUND, 'Looking at `App.tsx`')],
    })
    .mockResolvedValue({ session: baseSession(), events: [] });

  renderDetail(getSession);

  expect(await screen.findByText('acme/app')).toBeInTheDocument();
  expect(screen.getByText('Running')).toBeInTheDocument();
  expect(screen.getByText('Claude Agent')).toBeInTheDocument();
  expect(screen.getByText('Scouting')).toBeInTheDocument();

  // Markdown is rendered, not shown as raw source.
  expect(screen.getByRole('heading', { name: 'Do the thing' })).toBeInTheDocument();
  const code = screen.getByText('App.tsx');
  expect(code.tagName).toBe('CODE');
});

test('renders the Claude engine banner, agent actions, and the terminal run cost', async () => {
  const getSession = vi.fn().mockResolvedValue({
    session: baseSession({ state: SessionState.COMPLETED, totalCostUsd: 0.0421 }),
    events: [
      event(1, SessionEventKind.ENGINE_BANNER, '**Powered by Claude Agent** · manifest-less mode'),
      event(2, SessionEventKind.AGENT_ACTION, 'edited `src/App.tsx`'),
      event(3, SessionEventKind.RUN_COST, '**$0.0421** · 5 turns'),
    ],
  });

  renderDetail(getSession);

  // Banner is pinned (rendered as markdown, so "Powered by Claude Agent" is bold text).
  expect(await screen.findByText(/Powered by Claude Agent/)).toBeInTheDocument();
  // Agent action shows in the feed (the `src/App.tsx` path renders as inline code).
  expect(screen.getByText('src/App.tsx').tagName).toBe('CODE');
  // Terminal cost line derived from the session's total_cost_usd.
  expect(screen.getByText('$0.0421')).toBeInTheDocument();
});

test('a builtin session renders no banner and no cost line', async () => {
  const getSession = vi.fn().mockResolvedValue({
    session: baseSession({ engine: Engine.BUILTIN, state: SessionState.COMPLETED }),
    events: [event(1, SessionEventKind.SCOUTING_ROUND, 'round one')],
  });

  renderDetail(getSession);

  expect(await screen.findByText('round one')).toBeInTheDocument();
  expect(screen.queryByText(/Powered by/)).toBeNull();
  expect(screen.queryByText(/^Cost:/)).toBeNull();
  expect(screen.queryByText(/^\$/)).toBeNull();
});

test('does not render raw HTML embedded in task or event markdown', async () => {
  const getSession = vi.fn().mockResolvedValue({
    session: baseSession({ taskMarkdown: '<img src=x onerror="window.pwned = true">' }),
    events: [],
  });

  renderDetail(getSession);

  await screen.findByText('acme/app');

  expect(document.querySelector('img')).toBeNull();
  expect((window as unknown as { pwned?: boolean }).pwned).toBeUndefined();
});

test('polling appends new events without duplication, passing after_seq forward', async () => {
  const getSession = vi
    .fn()
    .mockResolvedValueOnce({
      session: baseSession(),
      events: [event(1, SessionEventKind.SCOUTING_ROUND, 'round one')],
    })
    .mockResolvedValueOnce({
      session: baseSession(),
      events: [event(2, SessionEventKind.HEALTH_CHECK, 'health check')],
    })
    // Further polls (state is still RUNNING) return nothing new.
    .mockResolvedValue({ session: baseSession(), events: [] });

  renderDetail(getSession);

  expect(await screen.findByText('round one')).toBeInTheDocument();
  expect(getSession).toHaveBeenNthCalledWith(1, { id: 'abc', afterSeq: 0 }, { headers: {} });

  expect(await screen.findByText('health check')).toBeInTheDocument();
  // The first event is still there — polling appends, it doesn't replace.
  expect(screen.getByText('round one')).toBeInTheDocument();
  // The second poll asks for events after the last seq it has already seen.
  expect(getSession).toHaveBeenNthCalledWith(2, { id: 'abc', afterSeq: 1 }, { headers: {} });

  // A poll that returns no new events keeps asking from the same afterSeq, never seq 0 again.
  await vi.waitFor(() => {
    const thirdCall = getSession.mock.calls[2];
    expect(thirdCall?.[0]).toEqual({ id: 'abc', afterSeq: 2 });
  });
});

test('re-subscribing (token refresh changes headers) does not duplicate the event feed', async () => {
  // The session is terminal, so each subscription polls exactly once (from afterSeq 0).
  const getSession = vi.fn().mockResolvedValue({
    session: baseSession({ state: SessionState.COMPLETED }),
    events: [event(1, SessionEventKind.SCOUTING_ROUND, 'round one')],
  });
  const client = { getSession } as unknown as SessionClient;

  function Harness({ headers }: { headers: HeadersInit }) {
    return (
      <MemoryRouter initialEntries={['/sessions/abc']}>
        <Routes>
          <Route
            path="/sessions/:id"
            element={
              <SessionDetailPage
                client={client}
                headers={headers}
                onUnauthorized={() => {}}
                pollIntervalMs={10}
              />
            }
          />
        </Routes>
      </MemoryRouter>
    );
  }

  const { rerender } = render(<Harness headers={{ Authorization: 'Bearer a' }} />);
  expect(await screen.findByText('round one')).toBeInTheDocument();

  // A refreshed token gives `headers` a new identity → the effect re-subscribes and re-polls from
  // afterSeq 0, replaying event seq 1. It must not appear twice.
  rerender(<Harness headers={{ Authorization: 'Bearer b' }} />);
  await vi.waitFor(() => expect(getSession).toHaveBeenCalledTimes(2));
  expect(screen.getAllByText('round one')).toHaveLength(1);
});

test('a terminal state stops polling and shows the PR link', async () => {
  const getSession = vi.fn().mockResolvedValue({
    session: baseSession({
      state: SessionState.COMPLETED,
      prUrl: 'https://github.com/acme/app/pull/7',
    }),
    events: [],
  });

  renderDetail(getSession);

  expect(await screen.findByRole('link', { name: 'View pull request' })).toHaveAttribute(
    'href',
    'https://github.com/acme/app/pull/7'
  );

  const callCountAfterFirstLoad = getSession.mock.calls.length;
  await new Promise((resolve) => setTimeout(resolve, 50));
  expect(getSession).toHaveBeenCalledTimes(callCountAfterFirstLoad);
});

test('a failed session shows the failure summary and a retry button that pre-fills the new-session form', async () => {
  const user = userEvent.setup();

  const getSession = vi.fn().mockResolvedValue({
    session: baseSession({
      state: SessionState.FAILED,
      failureSummary: 'Health check failed',
      repoFullName: 'acme/retry-me',
      taskMarkdown: 'original task',
    }),
    events: [],
  });

  renderDetail(getSession);

  expect(await screen.findByText('Health check failed')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Retry as new session' }));

  expect(await screen.findByText('New session page')).toBeInTheDocument();
});

test('the progress timeline concludes with a failed frame naming the phase it stopped in', async () => {
  const getSession = vi.fn().mockResolvedValue({
    session: baseSession({ state: SessionState.FAILED, failureSummary: 'boom' }),
    events: [
      event(1, SessionEventKind.IMPLEMENTATION_ATTEMPT, 'Implementation attempt 1 of 1'),
      event(2, SessionEventKind.PUBLISHING, 'Publishing the result as a pull request'),
    ],
  });

  renderDetail(getSession);

  // Without the terminal frame the feed would dead-end on "Publishing"; now it names the outcome.
  expect(await screen.findByText('✗ Failed during Publishing')).toBeInTheDocument();
});

test('the progress timeline concludes with a completed frame', async () => {
  const getSession = vi.fn().mockResolvedValue({
    session: baseSession({
      state: SessionState.COMPLETED,
      prUrl: 'https://github.com/acme/app/pull/7',
    }),
    events: [event(1, SessionEventKind.PUBLISHING, 'Publishing the result as a pull request')],
  });

  renderDetail(getSession);

  expect(await screen.findByText('✓ Completed')).toBeInTheDocument();
});
