import '@mantine/core/styles.css';
import { MantineProvider } from '@mantine/core';
import { Router } from './Router';
import { createCoreServiceClient } from './rpc/createCoreServiceClient';
import { theme } from './theme';

export default function RootComponent() {
  const coreServiceClient = createCoreServiceClient();

  return (
    <MantineProvider theme={theme}>
      <Router coreServiceClient={coreServiceClient} />
    </MantineProvider>
  );
}
