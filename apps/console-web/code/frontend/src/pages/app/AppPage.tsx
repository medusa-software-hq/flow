import { proxy } from 'valtio';
import { CApp } from '@/app/CApp';
import { AppView } from '@/pages/app/components/AppView/AppView';

export function AppPage() {
  const appLive = proxy(CApp.load());

  return <AppView appLive={appLive} />;
}
