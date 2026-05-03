import { Center, Stack, Text, UnstyledButton } from '@mantine/core';
import { useSnapshot } from 'valtio';
import { IApp } from '@/app/IApp';
import { ISessionWorkspaceTrampoline } from '@/app/session_workspace_trampoline/ISessionWorkspaceTrampoline';
import { SessionWorkspaceTrampolineStateKinds } from '@/app/session_workspace_trampoline/SessionWorkspaceTrampolineStateKinds';
import { AppTrampolineStateKinds } from '@/app_trampoline/AppStateKinds';
import { IAppTrampoline } from '@/app_trampoline/IAppTrampoline';
import { UAppTrampolineState } from '@/app_trampoline/IAppTrampolineState';
import classes from '../../AppPage.module.css';

type TSessionIconTone =
  | 'blue'
  | 'teal'
  | 'mint'
  | 'green'
  | 'lime'
  | 'yellow'
  | 'orange'
  | 'coral'
  | 'pink'
  | 'violet';

export interface SessionsRailProps {
  readonly appTrampolineLive: IAppTrampoline;
}

interface SessionRailViewModel {
  readonly sessionIcons: readonly SessionIconViewModel[];
  readonly onCreatePressed: (() => void) | null;
}

interface SessionIconViewModel {
  readonly key: string;
  readonly isSelected: boolean;
  readonly tone: TSessionIconTone | null;
  readonly content: string | null;
  readonly onPressed: (() => void) | null;
  readonly isPlaceholder: boolean;
}

export function SessionsRail({ appTrampolineLive }: SessionsRailProps) {
  const appTrampolineSnap = useSnapshot(appTrampolineLive);

  void appTrampolineSnap.currentState;
  const currentStateLive = appTrampolineLive.currentState;

  const loadedAppLive =
    currentStateLive.kind === AppTrampolineStateKinds.Loaded ? currentStateLive.loadedApp : null;

  const sessionRailViewModel = buildSessionRailViewModel({
    currentStateLive,
    loadedAppLive,
  });

  return (
    <aside className={classes.sessionsColumn}>
      <Stack align="center" gap="md">
        {sessionRailViewModel.sessionIcons.map((sessionIconViewModel) => (
          <RailSessionIcon
            key={sessionIconViewModel.key}
            sessionIconViewModel={sessionIconViewModel}
          />
        ))}

        <RailPlusIcon onPressed={sessionRailViewModel.onCreatePressed} />
      </Stack>
    </aside>
  );
}

interface RailIconTemplateProps {
  readonly isSelected: boolean;
  readonly tone: TSessionIconTone | null;
  readonly content: string | null;
  readonly onPressed: (() => void) | null;
  readonly isCreate: boolean;
  readonly isPlaceholder: boolean;
}

function RailIconTemplate({
  isSelected,
  tone,
  content,
  onPressed,
  isCreate,
  isPlaceholder,
}: RailIconTemplateProps) {
  return (
    <UnstyledButton
      className={classes.sessionTile}
      data-selected={isSelected || undefined}
      data-tone={tone ?? undefined}
      data-empty={isPlaceholder || undefined}
      data-create={isCreate || undefined}
      onClick={onPressed ?? undefined}
    >
      <Center className={classes.sessionTileInner}>
        {content === null ? null : (
          <Text fw={700} fz="lg" tt="none">
            {content}
          </Text>
        )}
      </Center>
    </UnstyledButton>
  );
}

interface RailSessionIconProps {
  readonly sessionIconViewModel: SessionIconViewModel;
}

function RailSessionIcon({ sessionIconViewModel }: RailSessionIconProps) {
  const { isSelected, tone, content, onPressed, isPlaceholder } = sessionIconViewModel;

  return (
    <RailIconTemplate
      isSelected={isSelected}
      tone={tone}
      content={content}
      onPressed={onPressed}
      isPlaceholder={isPlaceholder}
      isCreate={false}
    />
  );
}

interface RailPlusIconProps {
  readonly onPressed: (() => void) | null;
}

function RailPlusIcon({ onPressed }: RailPlusIconProps) {
  return (
    <RailIconTemplate
      isSelected={false}
      tone={null}
      content="+"
      onPressed={onPressed}
      isPlaceholder={false}
      isCreate
    />
  );
}

interface BuildSessionRailViewModelArgs {
  readonly currentStateLive: UAppTrampolineState;
  readonly loadedAppLive: IApp | null;
}

function buildSessionRailViewModel({
  currentStateLive,
  loadedAppLive,
}: BuildSessionRailViewModelArgs): SessionRailViewModel {
  switch (currentStateLive.kind) {
    case AppTrampolineStateKinds.Loading:
      return {
        sessionIcons: buildPlaceholderSessionIconViewModels('app-loading', 4),
        onCreatePressed: null,
      };

    case AppTrampolineStateKinds.Loaded:
      return {
        sessionIcons: Array.from(currentStateLive.loadedApp.sessionWorkspaceTrampolineById).map(
          ([sessionWorkspaceId, sessionWorkspaceTrampoline]) =>
            mapSessionTrampolineToViewModel({
              loadedAppLive: currentStateLive.loadedApp,
              sessionWorkspaceId,
              sessionWorkspaceTrampoline,
            })
        ),
        onCreatePressed:
          loadedAppLive === null ? null : () => loadedAppLive.createSessionWorkspace(),
      };

    case AppTrampolineStateKinds.Failed:
      throw new Error('Failed trampoline state should be handled above SessionsRail');
  }
}

function buildPlaceholderSessionIconViewModels(
  keyPrefix: string,
  count: number
): readonly SessionIconViewModel[] {
  return Array.from({ length: count }, (_, index) => ({
    key: `${keyPrefix}-${index}`,
    isSelected: false,
    tone: null,
    content: null,
    onPressed: null,
    isPlaceholder: true,
  }));
}

interface MapSessionTrampolineToViewModelArgs {
  readonly loadedAppLive: IApp;
  readonly sessionWorkspaceId: string;
  readonly sessionWorkspaceTrampoline: ISessionWorkspaceTrampoline;
}

function mapSessionTrampolineToViewModel({
  loadedAppLive,
  sessionWorkspaceId,
  sessionWorkspaceTrampoline,
}: MapSessionTrampolineToViewModelArgs): SessionIconViewModel {
  const onSelected = () => loadedAppLive.selectSessionWorkspace(sessionWorkspaceId);
  const isSelected = loadedAppLive.selectedSessionWorkspaceId === sessionWorkspaceId;

  switch (sessionWorkspaceTrampoline.currentState.kind) {
    case SessionWorkspaceTrampolineStateKinds.Creating:
      return {
        key: sessionWorkspaceId,
        isSelected,
        tone: null,
        content: null,
        onPressed: onSelected,
        isPlaceholder: false,
      };

    case SessionWorkspaceTrampolineStateKinds.Operational: {
      const sessionTitle =
        sessionWorkspaceTrampoline.currentState.operationalSessionWorkspace.currentState
          .sessionTitle;

      return {
        key: sessionWorkspaceId,
        isSelected,
        tone: getToneFromSessionId(sessionWorkspaceId),
        content: getSessionIconContent(sessionTitle),
        onPressed: onSelected,
        isPlaceholder: false,
      };
    }

    case SessionWorkspaceTrampolineStateKinds.Failed:
      return {
        key: sessionWorkspaceId,
        isSelected,
        tone: 'pink',
        content: '!',
        onPressed: onSelected,
        isPlaceholder: false,
      };
  }
}

function getToneFromSessionId(sessionId: string): TSessionIconTone {
  const tones: readonly TSessionIconTone[] = [
    'blue',
    'teal',
    'mint',
    'green',
    'lime',
    'yellow',
    'orange',
    'coral',
    'pink',
    'violet',
  ];
  let hash = 2166136261;

  for (const character of sessionId) {
    hash ^= character.charCodeAt(0);
    hash = Math.imul(hash, 16777619);
  }

  return tones[(hash >>> 0) % tones.length];
}

function getSessionIconContent(title: string): string {
  const normalizedTitle = title.trim();

  if (normalizedTitle === '') {
    return 'U';
  }

  return normalizedTitle[0]!.toUpperCase();
}
