import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { AppMainView } from '../AppMainView/AppMainView';
import { AppToolbar } from '../AppToolbar/AppToolbar';
import { SessionsRail } from '../SessionsRail/SessionsRail';
import classes from '../../AppPage.module.css';

export interface AppViewProps {
  readonly appTrampolineLive: IAppTrampoline;
}

export function AppView({ appTrampolineLive }: AppViewProps) {
  return (
    <div className={classes.page}>
      <AppToolbar appTrampolineLive={appTrampolineLive} />

      <div className={classes.contentRow}>
        <SessionsRail appTrampolineLive={appTrampolineLive} />

        <div className={classes.workspace}>
          <AppMainView appTrampolineLive={appTrampolineLive} />
        </div>
      </div>
    </div>
  );
}
