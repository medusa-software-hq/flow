import { useSnapshot } from 'valtio';
import { IApp } from '@/app/IApp';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { AppEmptyView } from '@/pages/app/AppEmptyView';
import { AppLoadingView } from '@/pages/app/AppLoadingView';
import { SessionWorkspaceFailedView } from '@/pages/app/SessionWorkspaceFailedView';
import { SessionWorkspaceLoadingView } from '@/pages/app/SessionWorkspaceLoadingView';
import { ISessionWorkspaceTrampoline } from '@/session_workspace_trampoline/ISessionWorkspaceTrampoline';
import { SessionWorkspaceTrampolineStateKinds } from '@/session_workspace_trampoline/SessionWorkspaceTrampolineStateKinds';
import { SessionView } from '../SessionView/SessionView';
import classes from '../../AppPage.module.css';

export interface AppMainViewProps {
  readonly appTrampolineLive: IAppTrampoline;
}

export function AppMainView({ appTrampolineLive }: AppMainViewProps) {
  const appTrampolineSnap = useSnapshot(appTrampolineLive);

  void appTrampolineSnap.currentState;
  const currentStateLive = appTrampolineLive.currentState;

  switch (currentStateLive.kind) {
    case AppTrampolineStateKinds.Loading:
      return <AppLoadingView />;

    case AppTrampolineStateKinds.Loaded:
      return <LoadedAppMainView appLive={currentStateLive.loadedApp} />;

    case AppTrampolineStateKinds.Failed:
      throw new Error('Failed trampoline state should be handled above AppView');
  }
}

interface LoadedAppMainViewProps {
  readonly appLive: IApp;
}

function LoadedAppMainView({ appLive }: LoadedAppMainViewProps) {
  const appSnap = useSnapshot(appLive);

  void appSnap.selectedSessionWorkspaceId;
  const selectedSessionWorkspaceTrampolineLive = appLive.selectedSessionWorkspaceTrampoline;

  if (selectedSessionWorkspaceTrampolineLive === null) {
    return (
      <div className={classes.centeredPaneContent}>
        <AppEmptyView />
      </div>
    );
  }

  return (
    <SelectedSessionWorkspaceTrampolineView
      key={appLive.selectedSessionWorkspaceId}
      sessionWorkspaceTrampolineLive={selectedSessionWorkspaceTrampolineLive}
    />
  );
}

interface SelectedSessionWorkspaceTrampolineViewProps {
  readonly sessionWorkspaceTrampolineLive: ISessionWorkspaceTrampoline;
}

function SelectedSessionWorkspaceTrampolineView({
  sessionWorkspaceTrampolineLive,
}: SelectedSessionWorkspaceTrampolineViewProps) {
  const sessionWorkspaceTrampolineSnap = useSnapshot(sessionWorkspaceTrampolineLive);

  void sessionWorkspaceTrampolineSnap.currentState;
  const currentStateLive = sessionWorkspaceTrampolineLive.currentState;

  switch (currentStateLive.kind) {
    case SessionWorkspaceTrampolineStateKinds.Loading:
      return (
        <div className={classes.centeredPaneContent}>
          <SessionWorkspaceLoadingView />
        </div>
      );

    case SessionWorkspaceTrampolineStateKinds.Loaded:
      return <SessionView sessionWorkspaceLive={currentStateLive.loadedSessionWorkspace} />;

    case SessionWorkspaceTrampolineStateKinds.Failed:
      return (
        <div className={classes.centeredPaneContent}>
          <SessionWorkspaceFailedView
            error={currentStateLive.error}
            retry={currentStateLive.retry}
          />
        </div>
      );
  }
}
