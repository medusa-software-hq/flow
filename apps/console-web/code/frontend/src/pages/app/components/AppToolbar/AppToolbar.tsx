import { Button, Group } from '@mantine/core';
import { useSnapshot } from 'valtio';
import { SessionWorkspaceStateKinds } from '@/app/SessionWorkspaceStateKinds';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { SessionWorkspaceTrampolineStateKinds } from '@/session_workspace_trampoline/SessionWorkspaceTrampolineStateKinds';
import classes from '../../AppPage.module.css';

export interface AppToolbarProps {
  readonly appTrampolineLive: IAppTrampoline;
}

export function AppToolbar({ appTrampolineLive }: AppToolbarProps) {
  const appTrampolineSnap = useSnapshot(appTrampolineLive);

  void appTrampolineSnap.currentState;
  const currentTrampolineState = appTrampolineLive.currentState;

  const selectedSessionWorkspaceTrampoline =
    currentTrampolineState.kind === AppTrampolineStateKinds.Loaded
      ? currentTrampolineState.loadedApp.selectedSessionWorkspaceTrampoline
      : null;

  const isEditing =
    selectedSessionWorkspaceTrampoline !== null &&
    selectedSessionWorkspaceTrampoline.currentState.kind ===
      SessionWorkspaceTrampolineStateKinds.Loaded &&
    selectedSessionWorkspaceTrampoline.currentState.loadedSessionWorkspace.currentState.kind ===
      SessionWorkspaceStateKinds.Editing;

  return (
    <Group className={classes.toolbar} justify="flex-end" p="md">
      <Button
        onClick={() => {
          if (currentTrampolineState.kind !== AppTrampolineStateKinds.Loaded) {
            return;
          }

          const selectedSessionWorkspaceTrampolineLive =
            currentTrampolineState.loadedApp.selectedSessionWorkspaceTrampoline;

          if (selectedSessionWorkspaceTrampolineLive === null) {
            return;
          }

          if (
            selectedSessionWorkspaceTrampolineLive.currentState.kind !==
            SessionWorkspaceTrampolineStateKinds.Loaded
          ) {
            return;
          }

          selectedSessionWorkspaceTrampolineLive.currentState.loadedSessionWorkspace.freeze();
        }}
        disabled={!isEditing}
      >
        Freeze For Testing
      </Button>
    </Group>
  );
}
