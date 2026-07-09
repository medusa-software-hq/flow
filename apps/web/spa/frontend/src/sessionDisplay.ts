import { timestampDate, type Timestamp } from '@bufbuild/protobuf/wkt';
import { SessionEventKind, SessionState } from './gen/medusa/session/v1/session_service_pb.ts';

export const sessionStateLabel: Record<SessionState, string> = {
  [SessionState.UNSPECIFIED]: 'Unknown',
  [SessionState.PENDING]: 'Pending',
  [SessionState.RUNNING]: 'Running',
  [SessionState.COMPLETED]: 'Completed',
  [SessionState.FAILED]: 'Failed',
};

export const sessionStateColor: Record<SessionState, string> = {
  [SessionState.UNSPECIFIED]: 'gray',
  [SessionState.PENDING]: 'gray',
  [SessionState.RUNNING]: 'blue',
  [SessionState.COMPLETED]: 'green',
  [SessionState.FAILED]: 'red',
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
};

export function formatTimestamp(timestamp: Timestamp | undefined): string {
  if (!timestamp) {
    return '—';
  }
  return timestampDate(timestamp).toLocaleString();
}
