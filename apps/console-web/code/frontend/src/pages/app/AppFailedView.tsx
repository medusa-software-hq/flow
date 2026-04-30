import { Alert, Button, Center, Stack, Title } from '@mantine/core';

interface AppFailedViewProps {
  readonly error: unknown;
  readonly retry: () => void;
}

export function AppFailedView({ error, retry }: AppFailedViewProps) {
  const errorMessage = error instanceof Error ? error.message : String(error);

  return (
    <Center h="100%" p="md">
      <Stack maw={480} w="100%" gap="md">
        <Title order={2}>App failed to load</Title>
        <Alert color="red" title="Startup error">
          {errorMessage}
        </Alert>
        <Button onClick={retry} w="fit-content">
          Retry
        </Button>
      </Stack>
    </Center>
  );
}
