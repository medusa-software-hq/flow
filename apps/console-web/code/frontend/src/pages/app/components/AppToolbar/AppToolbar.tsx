import { Button, Group } from '@mantine/core';
import { SessionWorkspaceStateKinds } from '@/app/SessionWorkspaceStateKinds';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import classes from '../../AppPage.module.css';

export interface AppToolbarProps {
  readonly appTrampolineLive: IAppTrampoline;
}

export function AppToolbar({ appTrampolineLive }: AppToolbarProps) {
  const currentTrampolineState = appTrampolineLive.currentState;

  const isEditing =
    currentTrampolineState.kind === AppTrampolineStateKinds.Loaded &&
    currentTrampolineState.loadedSessionWorkspace.currentState.kind ===
      SessionWorkspaceStateKinds.Editing;

  return (
    <Group className={classes.toolbar} justify="flex-end" p="md">
      <Button
        onClick={() => {
          if (currentTrampolineState.kind !== AppTrampolineStateKinds.Loaded) {
            return;
          }

          currentTrampolineState.loadedSessionWorkspace.freeze();
        }}
        disabled={!isEditing}
      >
        Freeze For Testing
      </Button>
    </Group>
  );
}
