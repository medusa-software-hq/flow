import { useMemo } from 'react';
import { useSnapshot } from 'valtio';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { CAppTrampoline } from '@/app_trampoline/CAppTrampoline';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { AppView } from '@/pages/app/components/AppView/AppView';
import { AppFailedView } from './AppFailedView';

export function AppPage() {
  const appTrampolineLive: IAppTrampoline = useMemo(() => CAppTrampoline.createProxied(), []);
  const appTrampolineSnap = useSnapshot(appTrampolineLive);

  void appTrampolineSnap.currentState;
  const currentStateLive = appTrampolineLive.currentState;

  switch (currentStateLive.kind) {
    case AppTrampolineStateKinds.Failed:
      return <AppFailedView error={currentStateLive.error} retry={currentStateLive.retry} />;

    case AppTrampolineStateKinds.Loading:
    case AppTrampolineStateKinds.Loaded:
      return <AppView appTrampolineLive={appTrampolineLive} />;
  }
}
