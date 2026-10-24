import { Injector, createEnvironmentInjector, EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Observable, Subject, delay, of, throwError } from 'rxjs';
import { ApiError } from '../http/api-error';
import { page } from '../../testing/test-utils';
import { Page } from '../models';
import {
  ListQuery,
  ListStore,
  initialListState,
  listFailed,
  listLoaded,
  listLoading,
  nextSort,
  withFilters,
  withPage,
  withSort,
} from './list-store';

describe('list state reducers (pure)', () => {
  const q: ListQuery<{ a: string }> = { filters: { a: 'x' }, page: 3, size: 20, sort: '' };

  it('state transitions', () => {
    const s0 = initialListState<number>();
    const s1 = listLoading(s0);
    expect(s1.status).toBe('loading');
    const s2 = listLoaded(page([1, 2]));
    expect(s2.status).toBe('loaded');
    const err = new ApiError(500, 'X', '', null, 'm', null);
    const s3 = listFailed(s2, err);
    expect(s3.status).toBe('error');
    expect(s3.data).toBe(s2.data); // old data kept for display
    expect(s0.status).toBe('idle'); // inputs not mutated
  });

  it('changing filters or sort resets to page 0; paging keeps filters', () => {
    expect(withFilters(q, { a: 'y' }).page).toBe(0);
    expect(withSort(q, 'name,asc').page).toBe(0);
    expect(withPage(q, 5, 50)).toEqual({ ...q, page: 5, size: 50 });
  });

  it('nextSort toggles direction and resets on a new field', () => {
    expect(nextSort('', 'name')).toBe('name,asc');
    expect(nextSort('name,asc', 'name')).toBe('name,desc');
    expect(nextSort('name,desc', 'name')).toBe('name,asc');
    expect(nextSort('name,desc', 'price')).toBe('price,asc');
  });
});

describe('ListStore', () => {
  let injector: EnvironmentInjector;

  beforeEach(() => {
    injector = createEnvironmentInjector([], TestBed.inject(EnvironmentInjector));
  });
  afterEach(() => injector.destroy());

  function make<T>(fetchFn: (q: ListQuery<{ q: string }>) => Observable<Page<T>>) {
    return runInInjectionContext(injector as Injector, () => new ListStore<T, { q: string }>(fetchFn, { q: '' }, 10, 'id,asc'));
  }

  it('loads on construction and exposes selectors', () => {
    const fetch = jasmine.createSpy('fetch').and.returnValue(of(page(['a', 'b'], 0, 10, 42)));
    const store = make<string>(fetch);
    expect(store.items()).toEqual(['a', 'b']);
    expect(store.total()).toBe(42);
    expect(store.loading()).toBeFalse();
    expect(store.empty()).toBeFalse();
    expect(fetch.calls.mostRecent().args[0]).toEqual({ filters: { q: '' }, page: 0, size: 10, sort: 'id,asc' });
  });

  it('reports empty and error states', () => {
    const empty = make<string>(() => of(page<string>([])));
    expect(empty.empty()).toBeTrue();
    const failing = make<string>(() => throwError(() => new ApiError(503, 'UPSTREAM_UNAVAILABLE', '', null, 'down', null)));
    expect(failing.error()?.userMessage).toBe('down');
    expect(failing.state().status).toBe('error');
  });

  it('refetches with page 0 when filters change and keeps the page on paging', () => {
    const fetch = jasmine.createSpy('fetch').and.callFake(() => of(page(['x'])));
    const store = make<string>(fetch);
    store.setPage(2, 50);
    expect(fetch.calls.mostRecent().args[0].page).toBe(2);
    expect(fetch.calls.mostRecent().args[0].size).toBe(50);
    store.setFilters({ q: 'abc' });
    expect(fetch.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({ page: 0, filters: { q: 'abc' } }));
    store.toggleSort('name');
    expect(fetch.calls.mostRecent().args[0].sort).toBe('name,asc');
    store.toggleSort('name');
    expect(fetch.calls.mostRecent().args[0].sort).toBe('name,desc');
  });

  it('ignores a stale response that arrives after a newer query (switchMap)', fakeAsync(() => {
    const slow = new Subject<Page<string>>();
    let call = 0;
    const store = make<string>(() => {
      call++;
      return call === 2 ? slow.pipe() : of(page(['first'])).pipe(delay(0));
    });
    tick();
    expect(store.items()).toEqual(['first']);
    store.setFilters({ q: 'slow' }); // call 2: pending
    store.setFilters({ q: 'fast' }); // call 3: resolves immediately (delay 0)
    tick();
    slow.next(page(['STALE']));
    tick();
    expect(store.items()).toEqual(['first']);
    expect(store.items()).not.toContain('STALE');
  }));
});
