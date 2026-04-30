import { ErrorViewTemplate } from './ErrorViewTemplate';

interface AppFailedViewProps {
  readonly error: unknown;
  readonly retry: () => void;
}

export function AppFailedView({ error, retry }: AppFailedViewProps) {
  return (
    <ErrorViewTemplate
      title="App failed to load"
      alertTitle="Startup error"
      error={error}
      retry={retry}
    />
  );
}
