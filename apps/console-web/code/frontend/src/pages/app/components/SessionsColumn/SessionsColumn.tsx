import { Center, Stack, Text, UnstyledButton } from '@mantine/core';
import classes from '../../AppPage.module.css';

const fakeSessions = [
  { id: 'current', label: 'i', tone: 'blue', selected: true },
  { id: 'vim', label: 'v', tone: 'lime', selected: false },
  { id: 'notes', label: '■', tone: 'ink', selected: false },
  { id: 'zap-1', label: 'z', tone: 'violet', selected: false },
  { id: 'g', label: 'g', tone: 'pink', selected: false },
  { id: 'zap-2', label: 'z', tone: 'violet', selected: false },
] as const;

export function SessionsColumn() {
  return (
    <aside className={classes.sessionsColumn}>
      <Stack align="center" gap="md">
        {fakeSessions.map((session) => (
          <UnstyledButton
            key={session.id}
            className={classes.sessionTile}
            data-selected={session.selected || undefined}
            data-tone={session.tone}
          >
            <Center className={classes.sessionTileInner}>
              <Text fw={700} fz="lg" tt="none">
                {session.label}
              </Text>
            </Center>
          </UnstyledButton>
        ))}

        <Text className={classes.sessionRailDivider} c="dimmed">
          +
        </Text>
      </Stack>
    </aside>
  );
}
