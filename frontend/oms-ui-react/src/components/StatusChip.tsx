export type Tone = 'success' | 'progress' | 'danger' | 'warn' | 'neutral';

const TONES: Record<string, Tone> = {
  FULFILLED: 'success',
  SENT: 'success',
  COMPLETED: 'success',
  COMMITTED: 'success',
  UP: 'success',
  OK: 'success',
  CREATED: 'progress',
  VALIDATED: 'progress',
  INVENTORY_RESERVED: 'progress',
  CONFIRMED: 'progress',
  PENDING: 'progress',
  PICKING: 'progress',
  PACKED: 'progress',
  SHIPPED: 'progress',
  RETRYING: 'progress',
  RESERVED: 'progress',
  REJECTED: 'danger',
  FAILED: 'danger',
  DOWN: 'danger',
  EXPIRED: 'danger',
  COMPENSATION_PENDING: 'warn',
  DEGRADED: 'warn',
  LOW: 'warn',
  CANCELLED: 'neutral',
  RELEASED: 'neutral',
};

const GLYPH: Record<Tone, string> = { success: '✓', progress: '…', danger: '✕', warn: '!', neutral: '–' };

export function toneFor(status: string): Tone {
  return TONES[status] ?? 'neutral';
}

/** Status pill. Meaning is conveyed by text AND a glyph, never by colour alone. */
export function StatusChip({ status, testId }: { status: string; testId?: string }) {
  const tone = toneFor(status);
  return (
    <span className={`chip tone-${tone}`} data-testid={testId}>
      <span aria-hidden="true" className="glyph">
        {GLYPH[tone]}
      </span>{' '}
      {status.replace(/_/g, ' ')}
    </span>
  );
}
