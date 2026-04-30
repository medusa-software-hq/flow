import { Loader, Stack, Text } from '@mantine/core';
import classes from './AppLoadingView.module.css';

export function SessionWorkspaceLoadingView() {
  return (
    <Stack className={classes.loadingView} gap="xs">
      <Loader size="lg" />
      <Text c="dimmed">Creating session...</Text>
    </Stack>
  );
}
