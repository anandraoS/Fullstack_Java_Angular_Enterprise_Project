import { EMPTY, Observable, defer, expand, switchMap, takeUntil, timer } from 'rxjs';

export interface PollOptions {
  initialMs: number;
  maxMs: number;
  /** backoff multiplier, default 1.5 */
  factor?: number;
}

/** Pure: delay before the n-th re-poll (0-based), capped at maxMs. */
export function backoffDelay(index: number, opts: PollOptions): number {
  return Math.min(opts.maxMs, Math.round(opts.initialMs * (opts.factor ?? 1.5) ** index));
}

/**
 * Polls `fetchFn` immediately and then with growing delays until `isDone(value)` is true (the terminal value is
 * emitted, then the stream completes) or `stop$` emits (e.g. component destroyed / user left the page).
 * An error from fetchFn terminates the stream so the caller can show it and offer a manual refresh.
 */
export function pollUntil<T>(
  fetchFn: () => Observable<T>,
  isDone: (value: T) => boolean,
  opts: PollOptions,
  stop$: Observable<unknown>,
): Observable<T> {
  return defer(fetchFn).pipe(
    expand((value, index) =>
      isDone(value) ? EMPTY : timer(backoffDelay(index, opts)).pipe(switchMap(() => fetchFn())),
    ),
    takeUntil(stop$),
  );
}
