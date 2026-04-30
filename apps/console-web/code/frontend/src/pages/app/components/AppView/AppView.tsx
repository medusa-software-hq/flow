import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { AppMainView } from '../AppMainView/AppMainView';
import { AppToolbar } from '../AppToolbar/AppToolbar';
import { SessionsColumn } from '../SessionsColumn/SessionsColumn';
import classes from '../../AppPage.module.css';

export interface AppViewProps {
  readonly appTrampolineLive: IAppTrampoline;
}

export function AppView({ appTrampolineLive }: AppViewProps) {
  return (
    <div className={classes.page}>
      <AppToolbar appTrampolineLive={appTrampolineLive} />

      <div className={classes.contentRow}>
        <SessionsColumn />

        <div className={classes.workspace}>
          <AppMainView appTrampolineLive={appTrampolineLive} />
        </div>
      </div>
    </div>
  );
}
