import { create } from '@bufbuild/protobuf';
import { timestampFromDate } from '@bufbuild/protobuf/wkt';
import { render, screen, userEvent } from '@test-utils';
import { MemoryRouter, Route, Routes } from 'react-router';
import {
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
}

function baseSession(overrides: SessionOverrides = {}) {
  return create(SessionSchema, {
    id: 'abc',
    repoFullName: 'acme/app',
    taskMarkdown: '# Do the thing',
    state: SessionState.RUNNING,
    createdBy: 'alice@example.com',
    createdAt: timestampFromDate(new Date('2026-01-01T00:00:00Z')),
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
  expect(screen.getByText('Scouting')).toBeInTheDocument();

  // Markdown is rendered, not shown as raw source.
  expect(screen.getByRole('heading', { name: 'Do the thing' })).toBeInTheDocument();
  const code = screen.getByText('App.tsx');
  expect(code.tagName).toBe('CODE');
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
