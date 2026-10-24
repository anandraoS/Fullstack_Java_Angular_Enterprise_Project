import { fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { backoffDelay, pollUntil } from './polling';

describe('backoffDelay', () => {
  it('grows geometrically and is capped', () => {
    const o = { initialMs: 1000, maxMs: 4000, factor: 2 };
    expect([0, 1, 2, 3, 10].map((i) => backoffDelay(i, o))).toEqual([1000, 2000, 4000, 4000, 4000]);
  });
  it('defaults to factor 1.5', () => {
    expect(backoffDelay(1, { initialMs: 1000, maxMs: 99999 })).toBe(1500);
  });
});

describe('pollUntil', () => {
  const opts = { initialMs: 100, maxMs: 1000, factor: 2 };

  it('polls with backoff until done, emits every value, then completes', fakeAsync(() => {
    const values = ['CREATED', 'VALIDATED', 'FULFILLED'];
    let n = 0;
    const seen: string[] = [];
    let completed = false;
    pollUntil(() => of(values[n++]), (v) => v === 'FULFILLED', opts, new Subject()).subscribe({
      next: (v) => seen.push(v),
      complete: () => (completed = true),
    });
    expect(seen).toEqual(['CREATED']);
    tick(100);
    expect(seen).toEqual(['CREATED', 'VALIDATED']);
    tick(199); // next delay is 200ms
    expect(seen.length).toBe(2);
    tick(1);
    expect(seen).toEqual(['CREATED', 'VALIDATED', 'FULFILLED']);
    expect(completed).toBeTrue();
    tick(5000);
    expect(n).toBe(3); // no polling after the terminal value
  }));

  it('stops when stop$ emits (takeUntil)', fakeAsync(() => {
    const stop = new Subject<void>();
    let calls = 0;
    pollUntil(() => of(++calls), () => false, opts, stop).subscribe();
    tick(100);
    expect(calls).toBe(2);
    stop.next();
    tick(10_000);
    expect(calls).toBe(2);
    discardPeriodicTasks();
  }));

  it('propagates errors and stops polling', fakeAsync(() => {
    let calls = 0;
    let error: unknown;
    pollUntil(
      () => (++calls < 2 ? of('x') : throwError(() => new Error('boom'))),
      () => false,
      opts,
      new Subject(),
    ).subscribe({ error: (e) => (error = e) });
    tick(100);
    expect((error as Error).message).toBe('boom');
    tick(10_000);
    expect(calls).toBe(2);
  }));
});
