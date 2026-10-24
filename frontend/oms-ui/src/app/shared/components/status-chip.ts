import { Component, computed, input } from '@angular/core';

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
@Component({
  selector: 'app-status-chip',
  template: `<span class="chip" [class]="'chip tone-' + tone()"
    ><span aria-hidden="true" class="glyph">{{ glyph() }}</span> {{ label() }}</span
  >`,
  styles: `
    .chip { display: inline-flex; align-items: center; gap: 4px; padding: 2px 10px; border-radius: 12px; font-size: 0.8rem; font-weight: 600; white-space: nowrap; border: 1px solid transparent; }
    .tone-success { background: #e3f4e6; color: #0b4a1a; border-color: #86c593; }
    .tone-progress { background: #e0ecff; color: #0a2f7a; border-color: #8fb1f0; }
    .tone-danger { background: #fde7e7; color: #7a0a0a; border-color: #e59a9a; }
    .tone-warn { background: #fff0cc; color: #5c3b00; border-color: #e0b84f; }
    .tone-neutral { background: #ececec; color: #2b2b2b; border-color: #b5b5b5; }
  `,
})
export class StatusChip {
  readonly status = input.required<string>();
  readonly tone = computed(() => toneFor(this.status()));
  readonly glyph = computed(() => GLYPH[this.tone()]);
  readonly label = computed(() => this.status().replace(/_/g, ' '));
}
