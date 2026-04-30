import { Center, Loader, Stack, Text } from '@mantine/core';

export function AppLoadingView() {
  return (
    <Center h="100%">
      <Stack align="center" gap="xs">
        <Loader size="lg" />
        <Text c="dimmed">Loading app...</Text>
      </Stack>
    </Center>
  );
}
