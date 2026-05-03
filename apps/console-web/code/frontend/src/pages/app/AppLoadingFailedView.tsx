import { ErrorViewTemplate } from './ErrorViewTemplate';

interface AppLoadingFailedProps {
  readonly error: unknown;
  readonly retry: () => void;
}

export function AppLoadingFailedView({ error, retry }: AppLoadingFailedProps) {
  return (
    <ErrorViewTemplate
      title="App failed to load"
      alertTitle="Startup error"
      error={error}
      retry={retry}
    />
  );
}
