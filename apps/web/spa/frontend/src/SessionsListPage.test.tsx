import { create } from '@bufbuild/protobuf';
import { timestampFromDate } from '@bufbuild/protobuf/wkt';
import { fireEvent, render, screen } from '@test-utils';
import { MemoryRouter, Route, Routes } from 'react-router';
import { Engine, SessionSchema, SessionState } from './gen/medusa/session/v1/session_service_pb.ts';
import { SessionsListPage } from './SessionsListPage.tsx';

function fakeClient(sessions: ReturnType<typeof create<typeof SessionSchema>>[]) {
  return {
    listSessions: () => Promise.resolve({ sessions }),
  } as unknown as Parameters<typeof SessionsListPage>[0]['client'];
}

function renderAt(path: string, client: ReturnType<typeof fakeClient>) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/sessions"
          element={<SessionsListPage client={client} headers={{}} onUnauthorized={() => {}} />}
        />
        <Route path="/sessions/:id" element={<div>Detail page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

test('renders sessions with state badge, created-by, and a PR link only when completed', async () => {
  const completed = create(SessionSchema, {
    id: 'a',
    repoFullName: 'acme/app',
    state: SessionState.COMPLETED,
    createdBy: 'alice@example.com',
    createdAt: timestampFromDate(new Date('2026-01-02T03:04:05Z')),
    prUrl: 'https://github.com/acme/app/pull/1',
    engine: Engine.CLAUDE,
  });
  const running = create(SessionSchema, {
    id: 'b',
    repoFullName: 'acme/other',
    state: SessionState.RUNNING,
    createdBy: 'bob@example.com',
    createdAt: timestampFromDate(new Date('2026-01-02T03:05:00Z')),
    engine: Engine.BUILTIN,
  });

  renderAt('/sessions', fakeClient([completed, running]));

  expect(await screen.findByText('acme/app')).toBeInTheDocument();
  expect(screen.getByText('acme/other')).toBeInTheDocument();
  expect(screen.getByText('Completed')).toBeInTheDocument();
  expect(screen.getByText('Running')).toBeInTheDocument();
  expect(screen.getByText('alice@example.com')).toBeInTheDocument();

  // Engine badges: the CLAUDE session reads "Claude Agent", the BUILTIN one "Builtin".
  expect(screen.getByText('Claude Agent')).toBeInTheDocument();
  expect(screen.getByText('Builtin')).toBeInTheDocument();

  // Only the completed session gets a PR link.
  expect(screen.getByRole('link', { name: 'View PR' })).toHaveAttribute(
    'href',
    'https://github.com/acme/app/pull/1'
  );
  expect(screen.getAllByRole('link', { name: 'View PR' })).toHaveLength(1);
});

test('shows an empty state when there are no sessions', async () => {
  renderAt('/sessions', fakeClient([]));

  expect(await screen.findByText('No sessions yet.')).toBeInTheDocument();
});

test('clicking a row navigates to the session detail route', async () => {
  const session = create(SessionSchema, {
    id: 'abc123',
    repoFullName: 'acme/app',
    state: SessionState.PENDING,
    createdBy: 'alice@example.com',
    createdAt: timestampFromDate(new Date()),
  });

  renderAt('/sessions', fakeClient([session]));

  const row = await screen.findByText('acme/app');
  fireEvent.click(row);

  expect(await screen.findByText('Detail page')).toBeInTheDocument();
});
