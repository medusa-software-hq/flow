import { Stack, Text, Title } from '@mantine/core';
import { useParams } from 'react-router';

/** Placeholder for the new-session form; replaced by story 08. */
export function NewSessionPageStub() {
  return (
    <Stack gap="md">
      <Title order={1}>New session</Title>
      <Text c="dimmed">Coming soon.</Text>
    </Stack>
  );
}

/** Placeholder for the session detail view; replaced by story 09. */
export function SessionDetailPageStub() {
  const { id } = useParams<{ id: string }>();

  return (
    <Stack gap="md">
      <Title order={1}>Session {id}</Title>
      <Text c="dimmed">Coming soon.</Text>
    </Stack>
  );
}
