import { useMemo } from 'react';
import { useSnapshot } from 'valtio';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { CAppTrampoline } from '@/app_trampoline/CAppTrampoline';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { AppView } from '@/pages/app/components/AppView/AppView';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { AppFailedView } from './AppFailedView';

interface AppPageProps {
  readonly coreServiceClient: CoreServiceClient;
}

export function AppPage({ coreServiceClient }: AppPageProps) {
  const appTrampolineLive: IAppTrampoline = useMemo(
    () => CAppTrampoline.createProxied({ coreServiceClient }),
    [coreServiceClient]
  );
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
