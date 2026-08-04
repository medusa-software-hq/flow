import type { Client } from '@connectrpc/connect';
import { Loader, Stack, Switch, Text, Title } from '@mantine/core';
import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import type { SettingsService } from './gen/medusa/settings/v1/settings_service_pb.ts';

/**
 * `/settings` — Flow's global Quick Settings. Currently just the Auto-merge toggle; future settings
 * slot in here as one more `Switch`/control bound to the same `Settings` message.
 */
export function SettingsPage({
  client,
  headers,
  onUnauthorized,
}: {
  client: Client<typeof SettingsService>;
  headers: HeadersInit;
  onUnauthorized: () => void;
}) {
  const [autoMerge, setAutoMerge] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const onUnauthorizedRef = useRef(onUnauthorized);
  onUnauthorizedRef.current = onUnauthorized;

  const handleError = useCallback((err: unknown) => {
    const message = err instanceof Error ? err.message : String(err);
    if (message.includes('401') || message.includes('unauthenticated')) {
      onUnauthorizedRef.current();
    } else {
      setError(message);
    }
    return message;
  }, []);

  useEffect(() => {
    let cancelled = false;
    client
      .getSettings({}, { headers })
      .then((response) => {
        if (!cancelled) {
          setAutoMerge(response.settings?.autoMerge ?? false);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          handleError(err);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [client, headers, handleError]);

  async function onToggle(next: boolean) {
    const previous = autoMerge;
    // Optimistic: flip immediately, roll back on failure.
    setAutoMerge(next);
    setSaving(true);
    setError(null);
    try {
      const response = await client.updateSettings({ settings: { autoMerge: next } }, { headers });
      const persisted = response.settings?.autoMerge ?? next;
      setAutoMerge(persisted);
      toast.success(persisted ? 'Auto-merge turned on.' : 'Auto-merge turned off.');
    } catch (err: unknown) {
      setAutoMerge(previous);
      toast.error(`Failed to update auto-merge: ${handleError(err)}`);
    } finally {
      setSaving(false);
    }
  }

  return (
    <Stack gap="md" maw={480}>
      <Title order={1}>Quick Settings</Title>

      {error !== null && (
        <Text c="red" size="sm">
          {error}
        </Text>
      )}

      {autoMerge === null ? (
        <Loader size="sm" />
      ) : (
        <Switch
          label="Auto-merge"
          description="When on, Flow arms GitHub auto-merge on the pull requests it drives, so a green PR merges itself instead of waiting for a human click."
          checked={autoMerge}
          disabled={saving}
          onChange={(event) => void onToggle(event.currentTarget.checked)}
        />
      )}
    </Stack>
  );
}
