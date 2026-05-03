import { useMemo } from 'react';
import { useSnapshot } from 'valtio';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { CAppTrampoline } from '@/app_trampoline/CAppTrampoline';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { AppView } from '@/pages/app/components/AppView/AppView';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { AppLoadingFailedView } from './AppLoadingFailedView';

interface AppPageProps {
  readonly coreServiceClient: CoreServiceClient;
}

export function AppPage({ coreServiceClient }: AppPageProps) {
  const appTrampolineLive: IAppTrampoline = useMemo(
    () => CAppTrampoline.setup({ coreServiceClient }),
    [coreServiceClient]
  );
  const appTrampolineSnap = useSnapshot(appTrampolineLive);

  void appTrampolineSnap.currentState;
  const currentStateLive = appTrampolineLive.currentState;

  switch (currentStateLive.kind) {
    case AppTrampolineStateKinds.Failed:
      return (
        <AppLoadingFailedView error={currentStateLive.error} retry={currentStateLive.reload} />
      );

    case AppTrampolineStateKinds.Loading:
    case AppTrampolineStateKinds.Loaded:
      return <AppView appTrampolineLive={appTrampolineLive} />;
  }
}
