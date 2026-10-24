import { DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, catchError, map, of, startWith, switchMap } from 'rxjs';
import { ApiError, normalizeError } from '../http/api-error';
import { Page } from '../models';

/** Query state shared by every paginated screen. */
export interface ListQuery<F> {
  filters: F;
  page: number;
  size: number;
  /** `field,asc|desc` or empty */
  sort: string;
}

export type LoadStatus = 'idle' | 'loading' | 'loaded' | 'error';

export interface ListState<T> {
  status: LoadStatus;
  data: Page<T> | null;
  error: ApiError | null;
}

/* ---- pure update functions ("reducers"): old state in, new state out, no side effects ---- */
export const initialListState = <T>(): ListState<T> => ({ status: 'idle', data: null, error: null });
export const listLoading = <T>(s: ListState<T>): ListState<T> => ({ ...s, status: 'loading', error: null });
export const listLoaded = <T>(data: Page<T>): ListState<T> => ({ status: 'loaded', data, error: null });
export const listFailed = <T>(s: ListState<T>, error: ApiError): ListState<T> => ({ ...s, status: 'error', error });

export function withFilters<F>(q: ListQuery<F>, filters: F): ListQuery<F> {
  return { ...q, filters, page: 0 }; // changing filters always returns to the first page
}
export function withPage<F>(q: ListQuery<F>, page: number, size: number): ListQuery<F> {
  return { ...q, page, size };
}
export function withSort<F>(q: ListQuery<F>, sort: string): ListQuery<F> {
  return { ...q, sort, page: 0 };
}

/** Next sort string when a column header is toggled. */
export function nextSort(current: string, field: string): string {
  const [f, dir] = current.split(',');
  if (f === field) return dir === 'asc' ? `${field},desc` : `${field},asc`;
  return `${field},asc`;
}

/**
 * Generic signal store for server-paginated lists (React/Redux mapping: slice + selectors + a request effect).
 * Must be constructed in an injection context (component field initializer or service constructor).
 * `switchMap` guarantees that a stale response can never overwrite a newer query.
 */
export class ListStore<T, F extends object> {
  private readonly _query;
  private readonly _state = signal<ListState<T>>(initialListState<T>());
  private readonly reload$ = new Subject<void>();

  readonly query;
  readonly state = this._state.asReadonly();
  readonly items = computed(() => this._state().data?.content ?? []);
  readonly total = computed(() => this._state().data?.totalElements ?? 0);
  readonly loading = computed(() => this._state().status === 'loading');
  readonly error = computed(() => this._state().error);
  readonly empty = computed(() => this._state().status === 'loaded' && this.items().length === 0);

  constructor(
    private readonly fetchFn: (q: ListQuery<F>) => Observable<Page<T>>,
    initialFilters: F,
    size = 20,
    sort = '',
  ) {
    this._query = signal<ListQuery<F>>({ filters: initialFilters, page: 0, size, sort });
    this.query = this._query.asReadonly();
    const destroyRef = inject(DestroyRef);
    this.reload$
      .pipe(
        startWith(undefined),
        switchMap(() => {
          this._state.update(listLoading);
          return this.fetchFn(this._query()).pipe(
            map((page) => listLoaded(page)),
            catchError((e: unknown) => of(listFailed(this._state(), normalizeError(e)))),
          );
        }),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe((s) => this._state.set(s));
  }

  setFilters(filters: F): void {
    this._query.update((q) => withFilters(q, filters));
    this.refresh();
  }

  setPage(page: number, size: number): void {
    this._query.update((q) => withPage(q, page, size));
    this.refresh();
  }

  toggleSort(field: string): void {
    this._query.update((q) => withSort(q, nextSort(q.sort, field)));
    this.refresh();
  }

  setSort(sort: string): void {
    this._query.update((q) => withSort(q, sort));
    this.refresh();
  }

  refresh(): void {
    this.reload$.next();
  }
}
