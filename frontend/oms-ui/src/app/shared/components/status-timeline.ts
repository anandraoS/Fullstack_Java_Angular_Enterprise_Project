import { DatePipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { OrderHistoryItem, OrderStatus } from '../../core/models';
import { StepState, TimelineStep, buildTimeline } from './timeline';

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
@Component({
  selector: 'app-status-timeline',
  imports: [DatePipe],
  template: `
    <ol class="timeline" aria-label="Order progress">
      @for (s of steps(); track s.status) {
        <li class="step" [class]="'step state-' + s.state + (s.branch ? ' branch' : '')" [attr.aria-current]="s.state === 'current' ? 'step' : null">
          <span class="dot" aria-hidden="true"></span>
          <span class="name">{{ label(s) }}</span>
          <span class="sr-only"> - {{ text(s.state) }}</span>
          <span class="time">{{ s.at ? (s.at | date: 'MMM d, HH:mm:ss') : '' }}</span>
        </li>
      }
    </ol>
  `,
  styles: `
    .timeline { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 8px 4px; }
    .step { display: flex; flex-direction: column; align-items: center; min-width: 110px; flex: 1; position: relative; text-align: center; padding-top: 4px; }
    .step::before { content: ''; position: absolute; top: 14px; left: -50%; width: 100%; height: 3px; background: #b0b7c3; z-index: 0; }
    .step:first-child::before { display: none; }
    .dot { width: 20px; height: 20px; border-radius: 50%; border: 3px solid #5b6472; background: #fff; z-index: 1; box-sizing: border-box; }
    .name { font-size: 0.8rem; font-weight: 600; margin-top: 4px; }
    .time { font-size: 0.7rem; color: var(--oms-text-muted); min-height: 1em; }
    .state-done .dot { background: #1b7a34; border-color: #1b7a34; }
    .state-current .dot { background: #0b3d91; border-color: #0b3d91; box-shadow: 0 0 0 4px #c9dafb; }
    .state-current .name { color: #0b3d91; }
    .state-upcoming .name, .state-skipped .name { color: #4b5563; }
    .state-skipped .dot { border-style: dashed; }
    .state-failed .dot { background: #b3261e; border-color: #b3261e; }
    .state-failed .name { color: #8c1d18; }
    .state-cancelled .dot { background: #4b5563; border-color: #4b5563; }
    .state-warning .dot { background: #b26a00; border-color: #b26a00; }
    .state-warning .name { color: #7a4900; }
    .branch .dot { border-radius: 4px; transform: rotate(45deg); width: 16px; height: 16px; margin-top: 2px; }
    @media (max-width: 600px) { .step { min-width: 80px; } }
  `,
})
export class StatusTimeline {
  readonly status = input.required<OrderStatus>();
  readonly history = input<OrderHistoryItem[]>([]);
  readonly steps = computed(() => buildTimeline(this.status(), this.history()));

  label(s: TimelineStep): string {
    return s.status.replace(/_/g, ' ');
  }

  text(state: StepState): string {
    return STATE_TEXT[state];
  }
}
