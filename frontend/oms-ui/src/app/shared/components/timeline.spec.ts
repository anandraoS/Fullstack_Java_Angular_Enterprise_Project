import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { OrderHistoryItem, OrderStatus } from '../../core/models';
import { StatusChip, toneFor } from './status-chip';
import { StatusTimeline } from './status-timeline';
import { buildTimeline } from './timeline';

const h = (to: OrderStatus, from: OrderStatus | null = null, at = '2026-01-01T00:00:0' + (to.length % 9) + 'Z'): OrderHistoryItem => ({
  fromStatus: from,
  toStatus: to,
  reason: null,
  actor: 'system',
  occurredAt: at,
});

const states = (steps: ReturnType<typeof buildTimeline>) => steps.map((s) => `${s.status}:${s.state}`);

describe('buildTimeline', () => {
  it('happy path in progress without history infers earlier steps as done', () => {
    expect(states(buildTimeline('INVENTORY_RESERVED'))).toEqual([
      'CREATED:done',
      'VALIDATED:done',
      'INVENTORY_RESERVED:current',
      'CONFIRMED:upcoming',
      'FULFILLED:upcoming',
    ]);
  });

  it('fulfilled order shows every step done', () => {
    expect(buildTimeline('FULFILLED').every((s) => s.state === 'done')).toBeTrue();
  });

  it('rejected after validation: later steps skipped, REJECTED branch failed', () => {
    const steps = buildTimeline('REJECTED', [h('CREATED'), h('VALIDATED', 'CREATED'), h('REJECTED', 'VALIDATED')]);
    expect(states(steps)).toEqual([
      'CREATED:done',
      'VALIDATED:done',
      'INVENTORY_RESERVED:skipped',
      'CONFIRMED:skipped',
      'FULFILLED:skipped',
      'REJECTED:failed',
    ]);
    expect(steps.at(-1)?.branch).toBeTrue();
  });

  it('cancelled via compensation shows the compensation branch as done and CANCELLED terminal', () => {
    const steps = buildTimeline('CANCELLED', [
      h('CREATED'),
      h('VALIDATED', 'CREATED'),
      h('INVENTORY_RESERVED', 'VALIDATED'),
      h('CONFIRMED', 'INVENTORY_RESERVED'),
      h('COMPENSATION_PENDING', 'CONFIRMED'),
      h('CANCELLED', 'COMPENSATION_PENDING'),
    ]);
    expect(states(steps).slice(3)).toEqual(['CONFIRMED:done', 'FULFILLED:skipped', 'COMPENSATION_PENDING:done', 'CANCELLED:cancelled']);
  });

  it('failed fulfillment ends in FAILED after compensation', () => {
    const steps = buildTimeline('FAILED', [h('CREATED'), h('CONFIRMED'), h('COMPENSATION_PENDING'), h('FAILED')]);
    expect(states(steps)).toContain('COMPENSATION_PENDING:done');
    expect(states(steps).at(-1)).toBe('FAILED:failed');
  });

  it('compensation in progress is a warning, not terminal', () => {
    const steps = buildTimeline('COMPENSATION_PENDING', [h('CREATED'), h('CONFIRMED'), h('COMPENSATION_PENDING')]);
    expect(states(steps).at(-1)).toBe('COMPENSATION_PENDING:warning');
  });

  it('branch status without history still appears', () => {
    expect(states(buildTimeline('CANCELLED')).at(-1)).toBe('CANCELLED:cancelled');
  });

  it('carries timestamps from history', () => {
    const steps = buildTimeline('VALIDATED', [h('CREATED', null, 'T1'), h('VALIDATED', 'CREATED', 'T2')]);
    expect(steps[0].at).toBe('T1');
    expect(steps[1].at).toBe('T2');
    expect(steps[2].at).toBeNull();
  });
});

@Component({
  imports: [StatusTimeline],
  template: `<app-status-timeline [status]="status" [history]="history" />`,
})
class Host {
  status: OrderStatus = 'CONFIRMED';
  history: OrderHistoryItem[] = [];
}

describe('StatusTimeline component', () => {
  let fixture: ComponentFixture<Host>;
  beforeEach(() => {
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
  });

  it('renders an ordered list with current step marked via aria-current and text alternatives', () => {
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('ol[aria-label="Order progress"]')).not.toBeNull();
    const items = el.querySelectorAll('li');
    expect(items.length).toBe(5);
    const current = el.querySelector('li[aria-current="step"]');
    expect(current?.textContent).toContain('CONFIRMED');
    expect(current?.querySelector('.sr-only')?.textContent).toContain('current step');
    expect(items[4].querySelector('.sr-only')?.textContent).toContain('not reached yet');
  });

  it('updates when the status changes to a terminal branch', () => {
    fixture.componentInstance.status = 'REJECTED';
    fixture.componentInstance.history = [h('CREATED'), h('VALIDATED'), h('REJECTED')];
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('li').length).toBe(6);
    expect(el.querySelector('li.branch.state-failed')?.textContent).toContain('REJECTED');
    expect(el.querySelector('li.state-skipped')).not.toBeNull();
  });
});

describe('StatusChip', () => {
  it('maps statuses to tones and always renders text', () => {
    expect(toneFor('FULFILLED')).toBe('success');
    expect(toneFor('REJECTED')).toBe('danger');
    expect(toneFor('COMPENSATION_PENDING')).toBe('warn');
    expect(toneFor('SOMETHING_NEW')).toBe('neutral');
    const f = TestBed.createComponent(StatusChip);
    f.componentRef.setInput('status', 'INVENTORY_RESERVED');
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('INVENTORY RESERVED');
    expect(f.nativeElement.querySelector('.glyph').getAttribute('aria-hidden')).toBe('true');
  });
});
