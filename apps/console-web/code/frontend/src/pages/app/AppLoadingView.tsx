import { Loader, Stack, Text } from '@mantine/core';
import classes from './AppLoadingView.module.css';

export function AppLoadingView() {
  return (
    <Stack className={classes.loadingView} gap="xs">
      <Loader size="lg" />
      <Text c="dimmed">Loading app...</Text>
    </Stack>
  );
}
