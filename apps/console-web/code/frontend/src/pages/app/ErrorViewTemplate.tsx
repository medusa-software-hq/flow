import { Alert, Button, Stack, Title } from '@mantine/core';
import classes from './ErrorViewTemplate.module.css';

interface ErrorViewTemplateProps {
  readonly title: string;
  readonly alertTitle: string;
  readonly error: unknown;
  readonly retry: () => void;
}

export function ErrorViewTemplate({ title, alertTitle, error, retry }: ErrorViewTemplateProps) {
  const errorMessage = error instanceof Error ? error.message : String(error);

  return (
    <Stack className={classes.errorView} gap="md">
      <Title order={2}>{title}</Title>
      <Alert color="red" title={alertTitle}>
        {errorMessage}
      </Alert>
      <Button onClick={retry} w="fit-content">
        Retry
      </Button>
    </Stack>
  );
}
