import { fireEvent, render, screen, waitFor } from '@test-utils';
import { SettingsPage } from './SettingsPage.tsx';

function fakeClient(
  autoMerge: boolean,
  overrides: Partial<{
    updateSettings: (req: { settings?: { autoMerge: boolean } }) => Promise<unknown>;
  }> = {}
) {
  return {
    getSettings: () => Promise.resolve({ settings: { autoMerge } }),
    updateSettings:
      overrides.updateSettings ??
      ((req: { settings?: { autoMerge: boolean } }) =>
        Promise.resolve({ settings: { autoMerge: req.settings?.autoMerge ?? false } })),
  } as unknown as Parameters<typeof SettingsPage>[0]['client'];
}

function renderPage(client: ReturnType<typeof fakeClient>) {
  return render(<SettingsPage client={client} headers={{}} onUnauthorized={() => {}} />);
}

test('loads and shows the current auto-merge value', async () => {
  renderPage(fakeClient(true));

  const toggle = await screen.findByRole('switch');
  expect(toggle).toBeChecked();
});

test('shows auto-merge off by default', async () => {
  renderPage(fakeClient(false));

  expect(await screen.findByRole('switch')).not.toBeChecked();
});

test('toggling calls updateSettings and reflects the persisted value', async () => {
  const updateSettings = vi.fn((req: { settings?: { autoMerge: boolean } }) =>
    Promise.resolve({ settings: { autoMerge: req.settings?.autoMerge ?? false } })
  );
  renderPage(fakeClient(false, { updateSettings }));

  const toggle = await screen.findByRole('switch');
  fireEvent.click(toggle);

  await waitFor(() =>
    expect(updateSettings).toHaveBeenCalledWith(
      { settings: { autoMerge: true } },
      expect.anything()
    )
  );
  expect(toggle).toBeChecked();
});

test('a failed update rolls the toggle back', async () => {
  const updateSettings = vi.fn(() => Promise.reject(new Error('boom')));
  renderPage(fakeClient(false, { updateSettings }));

  const toggle = await screen.findByRole('switch');
  fireEvent.click(toggle);

  await waitFor(() => expect(toggle).not.toBeChecked());
});
