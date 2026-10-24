import { useCallback, useReducer } from 'react';

/** Query state shared by every paginated screen (React/Redux counterpart of the query part of the Angular ListStore). */
export interface ListQuery<F> {
  filters: F;
  page: number;
  size: number;
  /** `field,asc|desc` or empty */
  sort: string;
}

/* ---- pure reducers: unit-tested without React ---- */
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

export type ListAction<F> =
  | { type: 'filters'; filters: F }
  | { type: 'page'; page: number; size: number }
  | { type: 'toggleSort'; field: string };

export function listReducer<F>(q: ListQuery<F>, a: ListAction<F>): ListQuery<F> {
  switch (a.type) {
    case 'filters':
      return withFilters(q, a.filters);
    case 'page':
      return withPage(q, a.page, a.size);
    case 'toggleSort':
      return withSort(q, nextSort(q.sort, a.field));
  }
}

export interface ListControls<F> {
  query: ListQuery<F>;
  setPage: (page: number, size: number) => void;
  toggleSort: (field: string) => void;
}

/**
 * Per-screen list query state. `filters` is passed in (already debounced by the screen); whenever it changes the page
 * resets to 0 (adjusted during render, no effect, no extra fetch). Server data comes from RTK Query keyed by this query,
 * so a stale response can never overwrite a newer one.
 */
export function useListQuery<F>(filters: F, size = 20, sort = ''): ListControls<F> {
  const [query, dispatch] = useReducer(listReducer<F>, { filters, page: 0, size, sort });
  if (JSON.stringify(query.filters) !== JSON.stringify(filters)) dispatch({ type: 'filters', filters });
  const setPage = useCallback((page: number, s: number) => dispatch({ type: 'page', page, size: s }), []);
  const toggleSort = useCallback((field: string) => dispatch({ type: 'toggleSort', field }), []);
  return { query, setPage, toggleSort };
}
