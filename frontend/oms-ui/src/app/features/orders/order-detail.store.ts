import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Observable, Subject, catchError, forkJoin, map, of } from 'rxjs';
import { OrderApi } from '../../core/api/apis';
import { AuthStore } from '../../core/auth/auth.store';
import { ConfigService } from '../../core/config.service';
import { ApiError, normalizeError } from '../../core/http/api-error';
import { Order, OrderHistoryItem, Saga, TERMINAL_STATUSES, CANCELLABLE_STATUSES } from '../../core/models';
import { pollUntil } from '../../core/state/polling';

export interface OrderSnapshot {
  order: Order;
  history: OrderHistoryItem[];
  saga: Saga | null;
}

export function isTerminal(order: Order | null): boolean {
  return !!order && TERMINAL_STATUSES.includes(order.status);
}

export function canCancel(order: Order | null): boolean {
  return !!order && CANCELLABLE_STATUSES.includes(order.status);
}

/**
 * Component-scoped store for the order detail page: loads order + history (+ saga for staff) and keeps refreshing
 * until the order reaches a terminal status. Polling uses RxJS timer with backoff and stops via takeUntil when the
 * store is destroyed, when the order is terminal, or when the user stops it.
 */
@Injectable()
export class OrderDetailStore {
  private readonly api = inject(OrderApi);
  private readonly auth = inject(AuthStore);
  private readonly cfg = inject(ConfigService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly _snapshot = signal<OrderSnapshot | null>(null);
  private readonly _error = signal<ApiError | null>(null);
  private readonly _loading = signal(false);
  private readonly _polling = signal(false);
  private stop$ = new Subject<void>();

  readonly snapshot = this._snapshot.asReadonly();
  readonly error = this._error.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly polling = this._polling.asReadonly();
  readonly order = computed(() => this._snapshot()?.order ?? null);
  readonly history = computed(() => this._snapshot()?.history ?? []);
  readonly saga = computed(() => this._snapshot()?.saga ?? null);
  readonly terminal = computed(() => isTerminal(this.order()));
  readonly cancellable = computed(() => canCancel(this.order()));

  constructor() {
    this.destroyRef.onDestroy(() => this.stopPolling());
  }

  private fetch(id: string): Observable<OrderSnapshot> {
    const saga$ = this.auth.isStaff() ? this.api.saga(id).pipe(catchError(() => of(null))) : of(null);
    return forkJoin({ order: this.api.get(id), history: this.api.history(id), saga: saga$ }).pipe(
      map((v) => v as OrderSnapshot),
    );
  }

  /** Initial load; then polls until terminal. */
  start(id: string): void {
    this.stopPolling();
    this._error.set(null);
    this._loading.set(true);
    this._polling.set(true);
    const { pollIntervalMs, pollMaxIntervalMs } = this.cfg.config();
    pollUntil(
      () => this.fetch(id),
      (s) => isTerminal(s.order),
      { initialMs: pollIntervalMs, maxMs: pollMaxIntervalMs },
      this.stop$,
    ).subscribe({
      next: (s) => {
        this._snapshot.set(s);
        this._loading.set(false);
        if (isTerminal(s.order)) this._polling.set(false);
      },
      error: (e: unknown) => {
        this._error.set(normalizeError(e));
        this._loading.set(false);
        this._polling.set(false);
      },
      complete: () => {
        this._loading.set(false);
        this._polling.set(false);
      },
    });
  }

  /** One-off refresh, e.g. after a cancel; resumes polling if the order is not terminal. */
  refresh(id: string): void {
    this.start(id);
  }

  stopPolling(): void {
    this.stop$.next();
    this._polling.set(false);
  }
}
