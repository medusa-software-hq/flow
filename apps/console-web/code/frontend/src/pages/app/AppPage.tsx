import { useMemo } from 'react';
import { useSnapshot } from 'valtio';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { CAppTrampoline } from '@/app_trampoline/CAppTrampoline';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { AppView } from '@/pages/app/components/AppView/AppView';
import { AppFailedView } from './AppFailedView';
import { AppLoadingView } from './AppLoadingView';

export function AppPage() {
  const appTrampolineLive: IAppTrampoline = useMemo(() => CAppTrampoline.createProxied(), []);
  const appTrampolineSnap = useSnapshot(appTrampolineLive);

  void appTrampolineSnap.currentState;
  const currentStateLive = appTrampolineLive.currentState;

  switch (currentStateLive.kind) {
    case AppTrampolineStateKinds.Loading:
      return <AppLoadingView />;
    case AppTrampolineStateKinds.Loaded:
      return <AppView appLive={currentStateLive.loadedApp} />;
    case AppTrampolineStateKinds.Failed:
      return <AppFailedView error={currentStateLive.error} retry={currentStateLive.retry} />;
  }
}
