import { ActionIcon, Group } from '@mantine/core';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { AppToolbarContent } from './AppToolbarContent';
import classes from '../../AppPage.module.css';

export interface AppToolbarProps {
  readonly appTrampolineLive: IAppTrampoline;
}

export function AppToolbar(props: { appTrampolineLive: IAppTrampoline }) {
  return (
    <div className={classes.toolbar}>
      <Group className={classes.toolbarMainRow} justify="space-between" wrap="nowrap">
        <ActionIcon
          className={classes.toolbarDocIcon}
          variant="filled"
          radius="md"
          size={40}
          aria-label="Session icon"
        >
          F
        </ActionIcon>
        <AppToolbarContent appTrampolineLive={props.appTrampolineLive} />
      </Group>
    </div>
  );
}
