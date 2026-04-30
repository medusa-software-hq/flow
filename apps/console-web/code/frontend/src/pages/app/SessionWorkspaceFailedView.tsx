import { ErrorViewTemplate } from './ErrorViewTemplate';

interface SessionWorkspaceFailedViewProps {
  readonly error: unknown;
  readonly retry: () => void;
}

export function SessionWorkspaceFailedView({ error, retry }: SessionWorkspaceFailedViewProps) {
  return (
    <ErrorViewTemplate
      title="Session couldn't be created"
      alertTitle="Session creation error"
      error={error}
      retry={retry}
    />
  );
}
