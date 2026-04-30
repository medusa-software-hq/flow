import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { AppLoadingView } from '@/pages/app/AppLoadingView';
import { SessionView } from '../SessionView/SessionView';

export interface AppMainViewProps {
  readonly appTrampolineLive: IAppTrampoline;
}

export function AppMainView({ appTrampolineLive }: AppMainViewProps) {
  const currentStateLive = appTrampolineLive.currentState;

  switch (currentStateLive.kind) {
    case AppTrampolineStateKinds.Loading:
      return <AppLoadingView />;

    case AppTrampolineStateKinds.Loaded:
      return <SessionView sessionWorkspaceLive={currentStateLive.loadedSessionWorkspace} />;

    case AppTrampolineStateKinds.Failed:
      throw new Error('Failed trampoline state should be handled above AppView');
  }
}
