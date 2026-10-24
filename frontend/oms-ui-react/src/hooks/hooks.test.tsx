import { act, renderHook } from '@testing-library/react';
import { backoffDelay } from './polling';
import { listReducer, nextSort, useListQuery, withFilters, withPage, withSort, type ListQuery } from './listQuery';
import { useDebouncedValue } from './useDebouncedValue';

const q: ListQuery<{ a: string }> = { filters: { a: 'x' }, page: 3, size: 20, sort: 'name,asc' };

describe('list query reducers (pagination, filter, sort)', () => {
  it('changing filters resets to the first page; paging and sorting behave', () => {
    expect(withFilters(q, { a: 'y' })).toEqual({ filters: { a: 'y' }, page: 0, size: 20, sort: 'name,asc' });
    expect(withPage(q, 5, 50)).toMatchObject({ page: 5, size: 50, sort: 'name,asc' });
    expect(withSort(q, 'price,desc')).toMatchObject({ sort: 'price,desc', page: 0 });
  });

  it('nextSort cycles asc -> desc -> asc and starts a new field ascending', () => {
    expect(nextSort('', 'name')).toBe('name,asc');
    expect(nextSort('name,asc', 'name')).toBe('name,desc');
    expect(nextSort('name,desc', 'name')).toBe('name,asc');
    expect(nextSort('name,desc', 'price')).toBe('price,asc');
  });

  it('listReducer dispatches every action type', () => {
    expect(listReducer(q, { type: 'filters', filters: { a: 'z' } }).page).toBe(0);
    expect(listReducer(q, { type: 'page', page: 1, size: 10 })).toMatchObject({ page: 1, size: 10 });
    expect(listReducer(q, { type: 'toggleSort', field: 'name' }).sort).toBe('name,desc');
  });

  it('useListQuery resets the page when the passed filters change, but not when they are re-created equal', () => {
    const { result, rerender } = renderHook(({ f }) => useListQuery(f, 20, 'sku,asc'), { initialProps: { f: { q: '' } } });
    act(() => result.current.setPage(4, 20));
    expect(result.current.query.page).toBe(4);
    rerender({ f: { q: '' } }); // new object, same content
    expect(result.current.query.page).toBe(4);
    rerender({ f: { q: 'bolt' } });
    expect(result.current.query.page).toBe(0);
    expect(result.current.query.filters).toEqual({ q: 'bolt' });
    act(() => result.current.toggleSort('sku'));
    expect(result.current.query.sort).toBe('sku,desc');
  });
});

describe('backoffDelay', () => {
  const o = { initialMs: 1500, maxMs: 8000 };
  it('grows by 1.5x per attempt and is capped', () => {
    expect([0, 1, 2, 3, 4, 5, 6].map((i) => backoffDelay(i, o))).toEqual([1500, 2250, 3375, 5063, 7594, 8000, 8000]);
  });
  it('supports a custom factor', () => {
    expect(backoffDelay(3, { initialMs: 100, maxMs: 10_000, factor: 2 })).toBe(800);
  });
});

describe('useDebouncedValue', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('only updates after the value has been stable for the delay', () => {
    const { result, rerender } = renderHook(({ v }) => useDebouncedValue(v, 300), { initialProps: { v: 'a' } });
    rerender({ v: 'ab' });
    rerender({ v: 'abc' });
    act(() => void vi.advanceTimersByTime(299));
    expect(result.current).toBe('a');
    act(() => void vi.advanceTimersByTime(2));
    expect(result.current).toBe('abc');
  });

  it('a fresh object literal each render does not loop or restart the timer', () => {
    const { result, rerender } = renderHook(({ v }) => useDebouncedValue({ v }, 100), { initialProps: { v: 1 } });
    rerender({ v: 1 });
    rerender({ v: 1 });
    act(() => void vi.advanceTimersByTime(150));
    expect(result.current).toEqual({ v: 1 });
    rerender({ v: 2 });
    act(() => void vi.advanceTimersByTime(150));
    expect(result.current).toEqual({ v: 2 });
  });
});
