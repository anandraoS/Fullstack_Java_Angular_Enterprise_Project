import { fmtDate, humanize } from '../core/format';
import type { OrderHistoryItem, OrderStatus } from '../models';
import { buildTimeline, type StepState } from './timeline';

const STATE_TEXT: Record<StepState, string> = {
  done: 'completed',
  current: 'current step',
  upcoming: 'not reached yet',
  skipped: 'skipped',
  failed: 'failed',
  cancelled: 'cancelled',
  warning: 'in progress (compensating)',
};

/** Order progress: happy path plus terminal branches. Each step has a text state for assistive tech. */
export function StatusTimeline({ status, history = [] }: { status: OrderStatus; history?: OrderHistoryItem[] }) {
  const steps = buildTimeline(status, history);
  return (
    <ol className="timeline" aria-label="Order progress">
      {steps.map((s) => (
        <li key={s.status} className={`step state-${s.state}${s.branch ? ' branch' : ''}`} aria-current={s.state === 'current' ? 'step' : undefined}>
          <span className="dot" aria-hidden="true" />
          <span className="name">{humanize(s.status)}</span>
          <span className="sr-only"> - {STATE_TEXT[s.state]}</span>
          <span className="time">{s.at ? fmtDate(s.at, 'short') : ''}</span>
        </li>
      ))}
    </ol>
  );
}
