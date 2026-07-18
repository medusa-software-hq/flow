import { render, screen, userEvent } from '@test-utils';
import { MemoryRouter, Route, Routes } from 'react-router';
import { Engine } from './gen/medusa/session/v1/session_service_pb.ts';
import { NewSessionForm } from './NewSessionForm.tsx';

type GitHubClient = Parameters<typeof NewSessionForm>[0]['gitHubClient'];
type SessionClient = Parameters<typeof NewSessionForm>[0]['sessionClient'];

function fakeGitHubClient(repoFullNames: string[]): GitHubClient {
  return {
    listRepositories: () =>
      Promise.resolve({
        repositories: repoFullNames.map((fullName) => ({
          fullName,
          defaultBranch: 'main',
          url: `https://github.com/${fullName}`,
        })),
      }),
  } as unknown as GitHubClient;
}

function renderForm(gitHubClient: GitHubClient, sessionClient: SessionClient) {
  return render(
    <MemoryRouter initialEntries={['/sessions/new']}>
      <Routes>
        <Route
          path="/sessions/new"
          element={
            <NewSessionForm
              gitHubClient={gitHubClient}
              sessionClient={sessionClient}
              headers={{}}
              onUnauthorized={() => {}}
            />
          }
        />
        <Route path="/sessions/:id" element={<div>Detail page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

test('submitting with empty fields is blocked client-side', async () => {
  const user = userEvent.setup();
  const createSession = vi.fn();
  const sessionClient = { createSession } as unknown as SessionClient;

  renderForm(fakeGitHubClient(['acme/app']), sessionClient);

  await user.click(screen.getByRole('button', { name: 'Create session' }));

  expect(await screen.findByText('Select a repository')).toBeInTheDocument();
  expect(screen.getByText('Describe the task')).toBeInTheDocument();
  expect(createSession).not.toHaveBeenCalled();
});

test('submitting with both fields filled calls CreateSession and navigates to the detail page', async () => {
  const user = userEvent.setup();
  const createSession = vi.fn().mockResolvedValue({ session: { id: 'new-id' } });
  const sessionClient = { createSession } as unknown as SessionClient;

  renderForm(fakeGitHubClient(['acme/app', 'acme/other']), sessionClient);

  const repoSelect = await screen.findByPlaceholderText('Select a repository');
  await user.click(repoSelect);
  await user.click(await screen.findByText('acme/app'));

  await user.type(screen.getByPlaceholderText('Describe what should be done…'), 'Fix the bug');
  await user.click(screen.getByRole('button', { name: 'Create session' }));

  expect(await screen.findByText('Detail page')).toBeInTheDocument();
  expect(createSession).toHaveBeenCalledWith(
    { repoFullName: 'acme/app', engine: Engine.UNSPECIFIED, taskMarkdown: 'Fix the bug' },
    { headers: {} }
  );
});

test('selecting the Claude Agent engine sends the numeric CLAUDE enum on CreateSession', async () => {
  const user = userEvent.setup();
  const createSession = vi.fn().mockResolvedValue({ session: { id: 'new-id' } });
  const sessionClient = { createSession } as unknown as SessionClient;

  renderForm(fakeGitHubClient(['acme/app']), sessionClient);

  const repoSelect = await screen.findByPlaceholderText('Select a repository');
  await user.click(repoSelect);
  await user.click(await screen.findByText('acme/app'));

  await user.click(screen.getByPlaceholderText('Select an engine'));
  await user.click(await screen.findByText('Claude Agent'));

  await user.type(screen.getByPlaceholderText('Describe what should be done…'), 'Fix the bug');
  await user.click(screen.getByRole('button', { name: 'Create session' }));

  expect(await screen.findByText('Detail page')).toBeInTheDocument();
  expect(createSession).toHaveBeenCalledWith(
    { repoFullName: 'acme/app', engine: Engine.CLAUDE, taskMarkdown: 'Fix the bug' },
    { headers: {} }
  );
});

test('surfaces a server-side rejection inline instead of navigating', async () => {
  const user = userEvent.setup();
  const createSession = vi.fn().mockRejectedValue(new Error('repo_full_name must be owner/name'));
  const sessionClient = { createSession } as unknown as SessionClient;

  renderForm(fakeGitHubClient(['acme/app']), sessionClient);

  const repoSelect = await screen.findByPlaceholderText('Select a repository');
  await user.click(repoSelect);
  await user.click(await screen.findByText('acme/app'));
  await user.type(screen.getByPlaceholderText('Describe what should be done…'), 'Fix the bug');
  await user.click(screen.getByRole('button', { name: 'Create session' }));

  expect(await screen.findByText('repo_full_name must be owner/name')).toBeInTheDocument();
  expect(screen.queryByText('Detail page')).not.toBeInTheDocument();
});
