import { timestampDate, type Timestamp } from '@bufbuild/protobuf/wkt';
import {
  Engine,
  SessionEventKind,
  SessionState,
} from './gen/medusa/session/v1/session_service_pb.ts';

export const sessionStateLabel: Record<SessionState, string> = {
  [SessionState.UNSPECIFIED]: 'Unknown',
  [SessionState.PENDING]: 'Pending',
  [SessionState.RUNNING]: 'Running',
  [SessionState.COMPLETED]: 'Completed',
  [SessionState.FAILED]: 'Failed',
  [SessionState.ABORTED]: 'Aborted',
};

export const sessionStateColor: Record<SessionState, string> = {
  [SessionState.UNSPECIFIED]: 'gray',
  [SessionState.PENDING]: 'gray',
  [SessionState.RUNNING]: 'blue',
  [SessionState.COMPLETED]: 'green',
  [SessionState.FAILED]: 'red',
  [SessionState.ABORTED]: 'orange',
};

export const engineLabel: Record<Engine, string> = {
  [Engine.UNSPECIFIED]: 'Builtin',
  [Engine.BUILTIN]: 'Builtin',
  [Engine.CLAUDE]: 'Claude Agent',
};

export const engineColor: Record<Engine, string> = {
  [Engine.UNSPECIFIED]: 'gray',
  [Engine.BUILTIN]: 'gray',
  [Engine.CLAUDE]: 'violet',
};

/** A session is still in flight — the detail page should keep polling. */
export function isSessionActive(state: SessionState): boolean {
  return state === SessionState.PENDING || state === SessionState.RUNNING;
}

export const sessionEventKindLabel: Record<SessionEventKind, string> = {
  [SessionEventKind.UNSPECIFIED]: 'Update',
  [SessionEventKind.WORKSPACE_PREPARING]: 'Preparing workspace',
  [SessionEventKind.HEALTH_GATE]: 'Initial health check',
  [SessionEventKind.SCOUTING_ROUND]: 'Scouting',
  [SessionEventKind.WORKSPACE_BRIEFING]: 'Workspace briefing',
  [SessionEventKind.IMPLEMENTATION_PLANNING]: 'Planning implementation',
  [SessionEventKind.IMPLEMENTATION_ATTEMPT]: 'Implementation attempt',
  [SessionEventKind.HEALTH_CHECK]: 'Health check',
  [SessionEventKind.PUBLISHING]: 'Publishing',
  // M4 (Claude Agent) event kinds.
  [SessionEventKind.AGENT_ACTION]: 'Agent action',
  [SessionEventKind.ENGINE_BANNER]: 'Engine',
  [SessionEventKind.RUN_COST]: 'Run cost',
};

export const sessionEventKindColor: Record<SessionEventKind, string> = {
  [SessionEventKind.UNSPECIFIED]: 'gray',
  [SessionEventKind.WORKSPACE_PREPARING]: 'gray',
  [SessionEventKind.HEALTH_GATE]: 'gray',
  [SessionEventKind.SCOUTING_ROUND]: 'gray',
  [SessionEventKind.WORKSPACE_BRIEFING]: 'gray',
  [SessionEventKind.IMPLEMENTATION_PLANNING]: 'gray',
  [SessionEventKind.IMPLEMENTATION_ATTEMPT]: 'gray',
  [SessionEventKind.HEALTH_CHECK]: 'gray',
  [SessionEventKind.PUBLISHING]: 'gray',
  [SessionEventKind.AGENT_ACTION]: 'violet',
  [SessionEventKind.ENGINE_BANNER]: 'violet',
  [SessionEventKind.RUN_COST]: 'teal',
};

/** Formats a USD cost for display, e.g. 0.0123 → "$0.0123". Returns "—" for undefined. */
export function formatCostUsd(costUsd: number | undefined): string {
  if (costUsd === undefined) {
    return '—';
  }
  return `$${costUsd.toFixed(4)}`;
}

/**
 * A proto Timestamp → `2026-07-23 15:18` in UTC, 24h clock. Unset or zero → "—".
 *
 * Pinned to match the CLI's `formatTimestamp` (same ISO date + 24h UTC shape, no `UTC` suffix)
 * — see `FlowFormat.kt`. Keep the two in lockstep; a change to one without the other is a bug.
 */
export function formatTimestamp(timestamp: Timestamp | undefined): string {
  if (!timestamp || (timestamp.seconds === 0n && timestamp.nanos === 0)) {
    return '—';
  }
  const date = timestampDate(timestamp);
  const pad = (n: number) => String(n).padStart(2, '0');
  const year = date.getUTCFullYear();
  const month = pad(date.getUTCMonth() + 1);
  const day = pad(date.getUTCDate());
  const hours = pad(date.getUTCHours());
  const minutes = pad(date.getUTCMinutes());
  return `${year}-${month}-${day} ${hours}:${minutes}`;
}
