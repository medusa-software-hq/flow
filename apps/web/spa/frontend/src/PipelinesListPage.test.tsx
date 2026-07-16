import { create } from '@bufbuild/protobuf';
import { timestampFromDate } from '@bufbuild/protobuf/wkt';
import { fireEvent, render, screen, waitFor } from '@test-utils';
import { MemoryRouter, Route, Routes } from 'react-router';
import {
  IssuePipelineSchema,
  IssuePipelineState,
} from './gen/medusa/pipeline/v1/pipeline_service_pb.ts';
import { PipelinesListPage } from './PipelinesListPage.tsx';

type Pipeline = ReturnType<typeof create<typeof IssuePipelineSchema>>;

function pipeline(fields: Partial<Pipeline> & { id: string }): Pipeline {
  return create(IssuePipelineSchema, {
    repoFullName: 'acme/app',
    issueNumber: 1,
    issueTitle: 'Do the thing',
    issueUrl: 'https://github.com/acme/app/issues/1',
    state: IssuePipelineState.IN_PROGRESS,
    updatedAt: timestampFromDate(new Date('2026-01-02T03:04:05Z')),
    ...fields,
  });
}

function fakeClient(
  pipelines: Pipeline[],
  overrides: Partial<{ clearIssuePipeline: (req: { id: string }) => Promise<unknown> }> = {}
) {
  return {
    listIssuePipelines: () => Promise.resolve({ pipelines }),
    clearIssuePipeline: overrides.clearIssuePipeline ?? (() => Promise.resolve({})),
  } as unknown as Parameters<typeof PipelinesListPage>[0]['client'];
}

function renderPage(client: ReturnType<typeof fakeClient>) {
  return render(
    <MemoryRouter initialEntries={['/pipelines']}>
      <Routes>
        <Route
          path="/pipelines"
          element={<PipelinesListPage client={client} headers={{}} onUnauthorized={() => {}} />}
        />
        <Route path="/sessions/:id" element={<div>Session detail</div>} />
      </Routes>
    </MemoryRouter>
  );
}

test('groups pipelines by repo and shows state + issue link', async () => {
  renderPage(
    fakeClient([
      pipeline({
        id: 'a',
        repoFullName: 'acme/app',
        issueNumber: 1,
        state: IssuePipelineState.PR_OPEN,
      }),
      pipeline({ id: 'b', repoFullName: 'other/repo', issueNumber: 2 }),
    ])
  );

  expect(await screen.findByText('acme/app')).toBeInTheDocument();
  expect(screen.getByText('other/repo')).toBeInTheDocument();
  expect(screen.getByText('PR open')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /#1 Do the thing/ })).toHaveAttribute(
    'href',
    'https://github.com/acme/app/issues/1'
  );
  // Busy-repo hint for the PR-open repo.
  expect(screen.getByText(/Waiting on PR review \/ merge checks for #1/)).toBeInTheDocument();
});

test('shows a Clear button only for uncleared FAILED pipelines', async () => {
  renderPage(
    fakeClient([
      pipeline({
        id: 'a',
        issueNumber: 1,
        state: IssuePipelineState.FAILED,
        failureSummary: 'boom',
      }),
      pipeline({ id: 'b', issueNumber: 2, state: IssuePipelineState.IN_PROGRESS }),
      pipeline({ id: 'c', issueNumber: 3, state: IssuePipelineState.FAILED, cleared: true }),
    ])
  );

  await screen.findByText('acme/app');
  // Exactly one Clear button — the uncleared FAILED row.
  expect(screen.getAllByRole('button', { name: 'Clear' })).toHaveLength(1);
  expect(screen.getByText('boom')).toBeInTheDocument();
});

test('clearing a failed pipeline confirms then calls the service', async () => {
  const clearIssuePipeline = vi.fn(() => Promise.resolve({}));
  renderPage(
    fakeClient(
      [
        pipeline({
          id: 'a',
          issueNumber: 7,
          state: IssuePipelineState.FAILED,
          failureSummary: 'x',
        }),
      ],
      { clearIssuePipeline }
    )
  );

  fireEvent.click(await screen.findByRole('button', { name: 'Clear' }));

  // Confirmation dialog explains the consequence.
  expect(await screen.findByText(/the repository is stopped/i)).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Clear pipeline' }));

  await waitFor(() =>
    expect(clearIssuePipeline).toHaveBeenCalledWith({ id: 'a' }, expect.anything())
  );
});

test('renders the outbox-stuck badge', async () => {
  renderPage(fakeClient([pipeline({ id: 'a', outboxStuck: true })]));

  expect(await screen.findByText('GitHub sync issue')).toBeInTheDocument();
});

test('empty state', async () => {
  renderPage(fakeClient([]));
  expect(await screen.findByText('No issue pipelines yet.')).toBeInTheDocument();
});
