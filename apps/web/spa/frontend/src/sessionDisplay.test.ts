import { create } from '@bufbuild/protobuf';
import { TimestampSchema, timestampFromDate } from '@bufbuild/protobuf/wkt';
import { formatTimestamp } from './sessionDisplay.ts';

// Pin the host timezone to something far from UTC so a test that only
// passes by accident of the CI runner's local timezone would fail here.
const originalTz = process.env.TZ;
beforeAll(() => {
  process.env.TZ = 'Pacific/Kiritimati'; // UTC+14, deliberately not UTC.
});
afterAll(() => {
  process.env.TZ = originalTz;
});

test('formats a timestamp as YYYY-MM-DD HH:mm in UTC regardless of local timezone', () => {
  const timestamp = timestampFromDate(new Date('2026-07-23T15:18:33Z'));
  expect(formatTimestamp(timestamp)).toBe('2026-07-23 15:18');
});

test('pads single-digit month, day, hour, and minute', () => {
  const timestamp = timestampFromDate(new Date('2026-01-02T03:04:05Z'));
  expect(formatTimestamp(timestamp)).toBe('2026-01-02 03:04');
});

test('renders an em dash for an undefined timestamp', () => {
  expect(formatTimestamp(undefined)).toBe('—');
});

test('renders an em dash for a zero (default instance) timestamp', () => {
  const timestamp = create(TimestampSchema, { seconds: 0n, nanos: 0 });
  expect(formatTimestamp(timestamp)).toBe('—');
});
