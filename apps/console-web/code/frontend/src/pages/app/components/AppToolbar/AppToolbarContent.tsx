import { Button, Group, TextInput } from '@mantine/core';
import React from 'react';
import { useSnapshot } from 'valtio';
import { IApp } from '@/app/IApp';
import { ISessionWorkspace } from '@/app/session_workspace/ISessionWorkspace';
import { SessionWorkspaceStateKinds } from '@/app/session_workspace/SessionWorkspaceStateKinds';
import { ISessionWorkspaceTrampoline } from '@/app/session_workspace_trampoline/ISessionWorkspaceTrampoline';
import { SessionWorkspaceTrampolineStateKinds } from '@/app/session_workspace_trampoline/SessionWorkspaceTrampolineStateKinds';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import classes from '../../AppPage.module.css';

interface AppToolbarContentViewModel {
  readonly onTitleChanged: React.ChangeEventHandler<HTMLInputElement, HTMLInputElement> | null;
  readonly onStartPressed: (() => void) | null;
}

export function AppToolbarContent(props: {
  appTrampolineLive: IAppTrampoline;
}): React.JSX.Element | null {
  const { appTrampolineLive } = props;

  const appTrampolineSnap = useSnapshot(appTrampolineLive);

  void appTrampolineSnap.currentState;
  const currentAppTrampolineStateLive = appTrampolineLive.currentState;

  switch (currentAppTrampolineStateLive.kind) {
    case AppTrampolineStateKinds.Loaded: {
      const appLive = currentAppTrampolineStateLive.loadedApp;

      return <AppToolbarContent$1 appLive={appLive} />;
    }

    default: {
      return null;
    }
  }
}

function AppToolbarContent$1(props: { appLive: IApp }): React.JSX.Element | null {
  const { appLive } = props;

  const appSnap = useSnapshot(appLive);

  void appSnap.selectedSessionWorkspaceTrampoline;
  const selectedSessionWorkspaceTrampolineLive = appLive.selectedSessionWorkspaceTrampoline;

  if (selectedSessionWorkspaceTrampolineLive !== null) {
    return (
      <AppToolbarContent$2
        selectedSessionWorkspaceTrampolineLive={selectedSessionWorkspaceTrampolineLive}
      />
    );
  } else {
    return null;
  }
}

function AppToolbarContent$2(props: {
  selectedSessionWorkspaceTrampolineLive: ISessionWorkspaceTrampoline;
}): React.JSX.Element | null {
  const { selectedSessionWorkspaceTrampolineLive } = props;

  const sessionWorkspaceTrampolineSnap = useSnapshot(selectedSessionWorkspaceTrampolineLive);

  void sessionWorkspaceTrampolineSnap.currentState;
  const currentTrampolineStateLive = selectedSessionWorkspaceTrampolineLive.currentState;

  switch (currentTrampolineStateLive.kind) {
    case SessionWorkspaceTrampolineStateKinds.Operational: {
      const selectedSessionWorkspaceLive = currentTrampolineStateLive.operationalSessionWorkspace;

      return <AppToolbarContent$3 selectedSessionWorkspaceLive={selectedSessionWorkspaceLive} />;
    }

    default: {
      return null;
    }
  }
}

function AppToolbarContent$3(props: {
  selectedSessionWorkspaceLive: ISessionWorkspace;
}): React.JSX.Element | null {
  const { selectedSessionWorkspaceLive } = props;

  const selectedSessionWorkspaceLiveSnap = useSnapshot(selectedSessionWorkspaceLive);

  void selectedSessionWorkspaceLiveSnap.currentState;
  const currentSessionWorkspaceStateLive = selectedSessionWorkspaceLive.currentState;

  const buildAppToolbarContentViewModel = (): AppToolbarContentViewModel => {
    switch (currentSessionWorkspaceStateLive.kind) {
      case SessionWorkspaceStateKinds.Editing: {
        return {
          onTitleChanged: (event) => {
            currentSessionWorkspaceStateLive.sessionTitle = event.currentTarget.value;
          },

          onStartPressed: () => {
            currentSessionWorkspaceStateLive.start();
          },
        };
      }

      case SessionWorkspaceStateKinds.Running: {
        return {
          onTitleChanged: null,
          onStartPressed: null,
        };
      }
    }
  };

  const viewModel = buildAppToolbarContentViewModel();

  return (
    <Group className={classes.toolbarContent} gap="sm" wrap="nowrap">
      <TextInput
        classNames={{ input: classes.toolbarTitleInput }}
        value={currentSessionWorkspaceStateLive.sessionTitle}
        placeholder="Untitled session"
        disabled={viewModel.onTitleChanged === null}
        onChange={viewModel.onTitleChanged ?? undefined}
      />

      <Button
        onClick={viewModel.onStartPressed ?? undefined}
        disabled={viewModel.onStartPressed === null}
      >
        Start
      </Button>
    </Group>
  );
}
