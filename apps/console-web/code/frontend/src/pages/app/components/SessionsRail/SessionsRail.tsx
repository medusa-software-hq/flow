import { Center, Stack, Text, UnstyledButton } from '@mantine/core';
import { useSnapshot } from 'valtio';
import { IAppSessionSummary, TAppSessionTone } from '@/app/IApp';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { UAppTrampolineState } from '@/app_trampoline/IAppTrampolineState';
import classes from '../../AppPage.module.css';

export interface SessionsRailProps {
  readonly appTrampolineLive: IAppTrampoline;
}

interface SessionIconViewModel {
  readonly key: string;
  readonly isSelected: boolean;
  readonly tone: TAppSessionTone | null;
  readonly label: string | null;
}

export function SessionsRail({ appTrampolineLive }: SessionsRailProps) {
  const appTrampolineSnap = useSnapshot(appTrampolineLive);

  void appTrampolineSnap.currentState;
  const currentStateLive = appTrampolineLive.currentState;

  const sessionIconViewModels = extractSessionIconViewModels(currentStateLive);

  return (
    <aside className={classes.sessionsColumn}>
      <Stack align="center" gap="md">
        {sessionIconViewModels.map((sessionIconViewModel) => (
          <SessionIcon key={sessionIconViewModel.key} sessionIconViewModel={sessionIconViewModel} />
        ))}

        <Text
          className={classes.sessionRailDivider}
          c={currentStateLive.kind === AppTrampolineStateKinds.Loading ? 'gray.4' : 'dimmed'}
        >
          +
        </Text>
      </Stack>
    </aside>
  );
}

interface SessionIconViewProps {
  readonly sessionIconViewModel: SessionIconViewModel;
}

function SessionIcon({ sessionIconViewModel }: SessionIconViewProps) {
  const { isSelected, label, tone } = sessionIconViewModel;

  return (
    <UnstyledButton
      className={classes.sessionTile}
      data-selected={isSelected || undefined}
      data-tone={tone ?? undefined}
      data-empty={label === null || undefined}
    >
      <Center className={classes.sessionTileInner}>
        {label === null ? null : (
          <Text fw={700} fz="lg" tt="none">
            {label}
          </Text>
        )}
      </Center>
    </UnstyledButton>
  );
}

function extractSessionIconViewModels(
  currentStateLive: UAppTrampolineState
): readonly SessionIconViewModel[] {
  switch (currentStateLive.kind) {
    case AppTrampolineStateKinds.Loading:
      return Array.from({ length: 6 }, (_, index) => ({
        key: `loading-${index}`,
        isSelected: false,
        tone: null,
        label: null,
      }));

    case AppTrampolineStateKinds.Loaded:
      return currentStateLive.loadedApp.sessions.map(mapSessionToViewModel);

    case AppTrampolineStateKinds.Failed:
      throw new Error('Failed trampoline state should be handled above SessionsRail');
  }
}

function mapSessionToViewModel(session: IAppSessionSummary): SessionIconViewModel {
  return {
    key: session.id,
    isSelected: session.isSelected,
    tone: session.tone,
    label: session.label,
  };
}
