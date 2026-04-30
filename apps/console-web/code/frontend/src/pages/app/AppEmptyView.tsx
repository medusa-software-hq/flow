import { Stack, Text } from '@mantine/core';
import classes from './AppLoadingView.module.css';

export function AppEmptyView() {
  return (
    <Stack className={classes.loadingView} gap="xs">
      <Text c="dimmed">No session selected yet.</Text>
      <Text c="gray.5" size="sm">
        Create a session from the rail to get started.
      </Text>
    </Stack>
  );
}
