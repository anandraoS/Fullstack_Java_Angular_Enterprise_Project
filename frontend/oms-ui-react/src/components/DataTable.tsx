import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { fmtDate, money } from '../core/format';
import { EmptyState, ErrorBanner, LoadingOverlay } from './feedback';
import { StatusChip } from './StatusChip';

export interface Column<T> {
  key: string;
  header: string;
  value: (row: T) => string | number | null | undefined;
  type?: 'text' | 'status' | 'date' | 'money' | 'link' | 'number';
  /** server sort field; presence makes the header a sort button */
  sortField?: string;
  /** router path for type 'link' */
  link?: (row: T) => string;
}

export interface DataTableProps<T> {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  loading?: boolean;
  error?: unknown;
  total?: number;
  page?: number;
  size?: number;
  sort?: string;
  caption?: string;
  paginate?: boolean;
  emptyTitle?: string;
  emptyMessage?: string;
  actions?: (row: T) => ReactNode;
  onPageChange?: (page: number, size: number) => void;
  onSortChange?: (field: string) => void;
  onRetry?: () => void;
}

/** Pure: aria-sort value for a column given the current `field,dir` sort string. */
export function ariaSortFor(sortField: string | undefined, sort: string): 'ascending' | 'descending' | 'none' | undefined {
  if (!sortField) return undefined;
  const [f, dir] = sort.split(',');
  if (f !== sortField) return 'none';
  return dir === 'desc' ? 'descending' : 'ascending';
}

function Cell<T>({ col, row }: { col: Column<T>; row: T }) {
  const v = col.value(row);
  switch (col.type) {
    case 'status':
      return <StatusChip status={String(v ?? '')} />;
    case 'link':
      return col.link ? <Link to={col.link(row)}>{v}</Link> : <>{v}</>;
    case 'date':
      return <>{fmtDate(typeof v === 'string' ? v : null)}</>;
    case 'money':
      return <>{money(v)}</>;
    default:
      return <>{v ?? ''}</>;
  }
}

/** Table with loading overlay, error banner, empty state, sortable headers and a paginator. */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  loading = false,
  error = null,
  total = 0,
  page = 0,
  size = 20,
  sort = '',
  caption = 'Data table',
  paginate = true,
  emptyTitle = 'No results',
  emptyMessage = 'Try changing the filters.',
  actions,
  onPageChange,
  onSortChange,
  onRetry,
}: DataTableProps<T>) {
  const numeric = (c: Column<T>) => c.type === 'money' || c.type === 'number';
  return (
    <div>
      <ErrorBanner error={error} onRetry={onRetry} />
      <div className="wrap" aria-busy={loading}>
        <LoadingOverlay active={loading} />
        {!error && !loading && rows.length === 0 ? (
          <EmptyState title={emptyTitle} message={emptyMessage} />
        ) : rows.length > 0 ? (
          // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- keyboard access to a horizontally scrolling region
          <div className="scroll" tabIndex={0} role="region" aria-label={`${caption} (scrollable)`}>
            <Table aria-label={caption} size="small">
              <TableHead>
                <TableRow>
                  {columns.map((col) => (
                    <TableCell key={col.key} aria-sort={ariaSortFor(col.sortField, sort)} align={numeric(col) ? 'right' : 'left'} sx={{ fontWeight: 600 }}>
                      {col.sortField ? (
                        <button type="button" className="sort" onClick={() => onSortChange?.(col.sortField as string)}>
                          {col.header}
                          <span aria-hidden="true">
                            {ariaSortFor(col.sortField, sort) === 'ascending' ? ' ▲' : ariaSortFor(col.sortField, sort) === 'descending' ? ' ▼' : ''}
                          </span>
                        </button>
                      ) : (
                        col.header
                      )}
                    </TableCell>
                  ))}
                  {actions && <TableCell sx={{ fontWeight: 600 }}>Actions</TableCell>}
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={rowKey(row)} hover>
                    {columns.map((col) => (
                      <TableCell key={col.key} align={numeric(col) ? 'right' : 'left'}>
                        <Cell col={col} row={row} />
                      </TableCell>
                    ))}
                    {actions && <TableCell>{actions(row)}</TableCell>}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ) : null}
      </div>
      {paginate && (
        <TablePagination
          component="div"
          count={total}
          page={page}
          rowsPerPage={size}
          rowsPerPageOptions={[10, 20, 50, 100]}
          showFirstButton
          showLastButton
          onPageChange={(_e, p) => onPageChange?.(p, size)}
          onRowsPerPageChange={(e) => onPageChange?.(0, Number(e.target.value))}
          slotProps={{ actions: { nextButton: { 'aria-label': 'Next page' }, previousButton: { 'aria-label': 'Previous page' } } }}
        />
      )}
    </div>
  );
}
