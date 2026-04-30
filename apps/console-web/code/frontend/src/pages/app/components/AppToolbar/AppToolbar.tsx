import { ActionIcon, Button, Group, TextInput } from '@mantine/core';
import { useSnapshot } from 'valtio';
import { IApp } from '@/app/IApp';
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
    <div className={classes.toolbar}>
      <Group className={classes.toolbarMainRow} justify="space-between" wrap="nowrap">
        <Group className={classes.toolbarIdentity} gap="sm" wrap="nowrap">
          <ActionIcon
            className={classes.toolbarDocIcon}
            variant="filled"
            radius="md"
            size={40}
            aria-label="Session icon"
          >
            F
          </ActionIcon>

          {currentTrampolineState.kind === AppTrampolineStateKinds.Loaded ? (
            <LoadedSessionNameField appLive={currentTrampolineState.loadedApp} />
          ) : null}
        </Group>

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
    </div>
  );
}

interface LoadedSessionNameFieldProps {
  readonly appLive: IApp;
}

function LoadedSessionNameField({ appLive }: LoadedSessionNameFieldProps) {
  const appSnap = useSnapshot(appLive);

  void appSnap.selectedSessionWorkspaceId;
  const selectedSessionWorkspaceId = appLive.selectedSessionWorkspaceId;

  void appSnap.selectedSessionTitle;
  const selectedSessionTitle = appLive.selectedSessionTitle;

  return (
    <TextInput
      classNames={{ input: classes.toolbarTitleInput }}
      value={selectedSessionTitle ?? ''}
      placeholder="Untitled session"
      disabled={selectedSessionWorkspaceId === null}
      onChange={(event) => {
        if (selectedSessionWorkspaceId === null) {
          return;
        }

        appLive.setSessionTitle(selectedSessionWorkspaceId, event.currentTarget.value);

        void appSnap.selectedSessionWorkspaceTrampoline;
        const selectedSessionWorkspaceTrampolineLive = appLive.selectedSessionWorkspaceTrampoline;

        if (
          selectedSessionWorkspaceTrampolineLive === null ||
          selectedSessionWorkspaceTrampolineLive.currentState.kind !==
            SessionWorkspaceTrampolineStateKinds.Loaded
        ) {
          return;
        }

        selectedSessionWorkspaceTrampolineLive.currentState.loadedSessionWorkspace.setTitle(
          event.currentTarget.value
        );
        void selectedSessionWorkspaceTrampolineLive.currentState.loadedSessionWorkspace.upload();
      }}
    />
  );
}
